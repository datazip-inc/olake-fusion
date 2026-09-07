/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Datazip Inc. in 2026
 */

package org.apache.amoro.server.utils;

import org.apache.amoro.optimizing.OptimizingType;
import org.apache.amoro.server.AmoroServiceContainer;
import org.apache.amoro.server.persistence.PlatformPropertyStore;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fire-and-forget usage telemetry.
 *
 * <p>Contract: no method of this class ever throws, blocks the calling thread, or changes the
 * behaviour of the code it is called from. Every public {@code track*} method builds its payload
 * and hands it to a dedicated single-threaded daemon executor with a bounded queue; when the queue
 * is full events are dropped rather than queued or retried.
 *
 * <p>Telemetry is turned off by the {@code TELEMETRY_DISABLED=true} environment variable or by the
 * {@code telemetry.disabled: true} AMS configuration option.
 *
 * <p>The install id identifies one OLake deployment across all of its products. The OLake UI owns
 * it and pushes it to AMS, which keeps it in {@code platform_property}; see {@link #resolveUserID}
 * for the full resolution order.
 */
public class Telemetry {
  private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

  private static final String TRACK_URL = "https://analytics.olake.io/mp/track";
  private static final String IPINFO_URL = "https://ipinfo.io/";
  private static final String IPIFY_URL = "https://api.ipify.org?format=text";
  private static final String NOT_FOUND_PLACEHOLDER = "NA";

  private static final String TELEMETRY_DISABLED_ENV = "TELEMETRY_DISABLED";
  /**
   * Directory holding the anonymous install id. Override it with {@code OLAKE_TELEMETRY_DIR} so
   * that deployments can point it at a persistent volume; without a persistent location a new
   * install id is generated on every restart.
   */
  private static final String TELEMETRY_DIR_ENV = "OLAKE_TELEMETRY_DIR";

  private static final String DEFAULT_TELEMETRY_DIR = "/tmp/olake-config/telemetry";
  private static final String USER_ID_FILE_NAME = "user_id";
  private static final int MAX_INSTALL_ID_LENGTH = 128;
  /** How long to wait before looking the install id up again while it is not stored yet. */
  private static final long INSTALL_ID_RETRY_INTERVAL_MS = 60_000L;

  private static final long TIMEOUT_SECONDS = 10L;
  /** Bounded so that a slow or unreachable collector can never accumulate work. */
  private static final int MAX_PENDING_EVENTS = 256;

  /** Set from the AMS configuration; {@code null} means "not configured, fall back to the env". */
  private static volatile Boolean configuredDisabled;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Executor executor;
  private final CompletableFuture<Void> initFuture;

  private volatile String ipAddress = NOT_FOUND_PLACEHOLDER;
  private volatile PlatformInfo platform;
  private volatile LocationInfo locationInfo;
  private volatile String userID;
  /**
   * False while {@link #userID} is a throwaway id, so later events keep looking for the real one.
   */
  private volatile boolean userIDDurable;

  private final PlatformPropertyStore propertyStore = new PlatformPropertyStore();

  private volatile long nextInstallIdLookupAt;

  public record PlatformInfo(String os, String arch, String deviceCpu) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LocationInfo(String country, String region, String city) {}

  // Thread-safe Singleton Setup
  private static final class InstanceHolder {
    private static final Telemetry INSTANCE = new Telemetry();
  }

  public static Telemetry getInstance() {
    return InstanceHolder.INSTANCE;
  }

  /**
   * Applies the AMS configuration. Called once while the server boots, before any event is tracked.
   */
  public static void configure(boolean disabled) {
    configuredDisabled = disabled;
    if (disabled) {
      LOG.info("Telemetry is disabled by configuration");
    }
  }

  /**
   * Nothing here may throw. Callers reach the singleton from a {@code finally} block, where an
   * {@link ExceptionInInitializerError} would replace the outcome of the work being tracked; a
   * telemetry object that failed to build simply reports nothing.
   */
  private Telemetry() {
    HttpClient client = null;
    ObjectMapper mapper = null;
    Executor telemetryExecutor = null;
    CompletableFuture<Void> init = null;
    try {
      client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();
      mapper = new ObjectMapper();
      telemetryExecutor = newTelemetryExecutor();
      this.platform = gatherPlatformInfo();
      this.locationInfo = unknownLocation();
    } catch (Throwable t) {
      LOG.debug("Failed to set up telemetry, no events will be reported: {}", t.getMessage());
    }
    this.httpClient = client;
    this.objectMapper = mapper;
    this.executor = telemetryExecutor;
    // The install id lives in the database, so it is resolved on the telemetry thread when the
    // first event is dispatched rather than here, where the data source may not be up yet.
    if (telemetryExecutor != null) {
      try {
        init = initAsync();
      } catch (Throwable t) {
        LOG.debug("Failed to start telemetry context lookup: {}", t.getMessage());
      }
    }
    this.initFuture = init;
  }

  /**
   * A single daemon thread with a bounded queue. Telemetry must never borrow threads from {@link
   * java.util.concurrent.ForkJoinPool#commonPool()}, which the rest of the JVM shares, because the
   * context lookups below are blocking calls.
   */
  private static Executor newTelemetryExecutor() {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_EVENTS),
            runnable -> {
              Thread thread = new Thread(runnable, "olake-telemetry");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());
    pool.allowCoreThreadTimeOut(false);
    return pool;
  }

  private static LocationInfo unknownLocation() {
    return new LocationInfo(NOT_FOUND_PLACEHOLDER, NOT_FOUND_PLACEHOLDER, NOT_FOUND_PLACEHOLDER);
  }

  public boolean isTelemetryDisabled() {
    String disabledEnv = System.getenv(TELEMETRY_DISABLED_ENV);
    if (disabledEnv != null && Boolean.parseBoolean(disabledEnv)) {
      return true;
    }
    return Boolean.TRUE.equals(configuredDisabled);
  }

  private CompletableFuture<Void> initAsync() {
    return CompletableFuture.runAsync(
            () -> {
              try {
                if (isTelemetryDisabled()) {
                  return;
                }
                this.ipAddress = fetchOutboundIP();
                this.locationInfo = fetchLocationFromIP(this.ipAddress);
              } catch (Throwable t) {
                LOG.debug("Failed to initialize telemetry context: {}", t.getMessage());
              }
            },
            executor)
        .completeOnTimeout(null, 2 * TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private String fetchOutboundIP() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(IPIFY_URL))
              .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return response.body().trim();
      }
    } catch (Exception e) {
      LOG.debug("Failed to fetch outbound IP: {}", e.getMessage());
    }
    return NOT_FOUND_PLACEHOLDER;
  }

  private LocationInfo fetchLocationFromIP(String ip) {
    if (NOT_FOUND_PLACEHOLDER.equals(ip)) {
      return unknownLocation();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(IPINFO_URL + ip + "/json"))
              .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return objectMapper.readValue(response.body(), LocationInfo.class);
      }
    } catch (Exception e) {
      LOG.debug("Failed to fetch location context for IP {}: {}", ip, e.getMessage());
    }
    return unknownLocation();
  }

  private PlatformInfo gatherPlatformInfo() {
    String os = System.getProperty("os.name", "Unknown").toLowerCase();
    String arch = System.getProperty("os.arch", "Unknown");
    int cores = Runtime.getRuntime().availableProcessors();

    return new PlatformInfo(os, arch, cores + " cores");
  }

  private static Path userIdFile() {
    String dir = System.getenv(TELEMETRY_DIR_ENV);
    if (dir == null || dir.trim().isEmpty()) {
      dir = DEFAULT_TELEMETRY_DIR;
    }
    return Paths.get(dir, USER_ID_FILE_NAME);
  }

  /**
   * Resolves the install id, in order:
   *
   * <ol>
   *   <li>{@code platform_property}, where the OLake UI pushes the id it owns;
   *   <li>the shared {@code user_id} file, when an older OLake deployment mounts one;
   *   <li>a generated id.
   * </ol>
   *
   * <p>The last two are written back to {@code platform_property} so the id survives restarts even
   * when the UI never pushes one, for instance because telemetry is off on the UI side. A push from
   * the UI overwrites whatever is stored, so AMS choosing an id on its own cannot win permanently.
   *
   * <p>Until an id has been stored, every event retries the lookup, throttled to one attempt per
   * {@link #INSTALL_ID_RETRY_INTERVAL_MS} so a degraded database is not queried per event.
   */
  private String resolveUserID() {
    String cached = this.userID;
    long now = System.currentTimeMillis();
    if (cached != null && now < nextInstallIdLookupAt) {
      return cached;
    }
    nextInstallIdLookupAt = now + INSTALL_ID_RETRY_INTERVAL_MS;

    String fromDb = readInstallIdFromDb();
    if (fromDb != null) {
      this.userID = fromDb;
      this.userIDDurable = true;
      return fromDb;
    }

    if (cached != null) {
      // Keep reporting under the id this process already picked, and try to make it durable.
      return adoptInstallId(cached);
    }

    String resolved = readSharedUserID();
    if (resolved == null) {
      resolved = generateUserID();
      LOG.debug("No OLake install id found, generated one for this deployment");
    }
    String effective = adoptInstallId(resolved);
    this.userID = effective;
    return effective;
  }

  private String readInstallIdFromDb() {
    try {
      String stored = propertyStore.get(PlatformPropertyStore.TELEMETRY_INSTALL_ID);
      return stored == null || stored.isEmpty() ? null : stored;
    } catch (Throwable t) {
      // Missing table on a database whose migrations have not run, or no data source at all.
      LOG.debug("Failed to read telemetry install id from the database: {}", t.getMessage());
      return null;
    }
  }

  /** Persists an id resolved outside the database and returns the value that ended up stored. */
  private String adoptInstallId(String id) {
    try {
      String effective = propertyStore.putIfAbsent(PlatformPropertyStore.TELEMETRY_INSTALL_ID, id);
      this.userIDDurable = true;
      return effective;
    } catch (Throwable t) {
      LOG.debug("Failed to persist telemetry install id: {}", t.getMessage());
      return id;
    }
  }

  /**
   * Applies the install id pushed by the OLake UI. The UI owns this id, so it overwrites whatever
   * AMS resolved on its own.
   */
  public String applyInstallId(String id) {
    String normalized = normalizeInstallId(id);
    propertyStore.put(PlatformPropertyStore.TELEMETRY_INSTALL_ID, normalized);
    this.userID = normalized;
    this.userIDDurable = true;
    return normalized;
  }

  private static String normalizeInstallId(String id) {
    String normalized = id == null ? "" : id.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Install id is empty");
    }
    if (normalized.length() > MAX_INSTALL_ID_LENGTH) {
      throw new IllegalArgumentException(
          "Install id is longer than " + MAX_INSTALL_ID_LENGTH + " characters");
    }
    if (!normalized.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException(
          "Install id may only contain letters, digits, '-' and '_'");
    }
    return normalized;
  }

  private String readSharedUserID() {
    Path shared = userIdFile();
    try {
      if (Files.exists(shared)) {
        String id = Files.readString(shared, StandardCharsets.UTF_8).trim().replace("\"", "");
        if (!id.isEmpty()) {
          return id;
        }
      }
    } catch (IOException e) {
      LOG.debug("Failed to read shared telemetry user id at {}: {}", shared, e.getMessage());
    }
    return null;
  }

  private String generateUserID() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(Instant.now().toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }
  }

  /**
   * Single entry point for every event. The payload is built lazily inside the guard so that
   * neither payload construction nor dispatch can propagate a failure to the caller.
   */
  private void sendEvent(String eventName, Supplier<Map<String, Object>> propsSupplier) {
    try {
      if (isTelemetryDisabled() || initFuture == null || executor == null) {
        return;
      }
      Map<String, Object> snapshot = new HashMap<>(propsSupplier.get());
      initFuture.thenRunAsync(() -> dispatchEvent(eventName, snapshot), executor);
    } catch (Throwable t) {
      LOG.debug("Telemetry dispatch scheduling failed for {}: {}", eventName, t.getMessage());
    }
  }

  private void dispatchEvent(String eventName, Map<String, Object> props) {
    try {
      if (httpClient == null || objectMapper == null) {
        return;
      }
      PlatformInfo p = platform != null ? platform : gatherPlatformInfo();
      LocationInfo loc = locationInfo != null ? locationInfo : unknownLocation();
      Map<String, Object> enrichedProperties = new HashMap<>(props);
      enrichedProperties.put("os", p.os());
      enrichedProperties.put("arch", p.arch());
      enrichedProperties.put("num_cpu", p.deviceCpu());
      enrichedProperties.put("ip_address", ipAddress);
      enrichedProperties.put("location", loc);
      enrichedProperties.put("distinct_id", userIDDurable ? userID : resolveUserID());
      enrichedProperties.put("time", System.currentTimeMillis() / 1000L);
      enrichedProperties.put("event_original_name", eventName);

      Map<String, Object> baseBody = new HashMap<>();
      baseBody.put("event", eventName);
      baseBody.put("properties", enrichedProperties);

      String jsonPayload = objectMapper.writeValueAsString(baseBody);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(TRACK_URL))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
              .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
              .build();

      httpClient
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .exceptionally(
              err -> {
                LOG.debug("Async tracking dispatch failed gracefully: {}", err.getMessage());
                return null;
              });

    } catch (Throwable t) {
      LOG.debug("Telemetry delivery structure failure: {}", t.getMessage());
    }
  }

  private String optimizationTypeHelper(OptimizingType optimizationType) {
    if (optimizationType == null) {
      return NOT_FOUND_PLACEHOLDER;
    }
    switch (optimizationType) {
      case MINOR:
        return "LITE";
      case MAJOR:
        return "MEDIUM";
      default:
        return optimizationType.name();
    }
  }

  public void trackOptimizationStarted(OptimizingType optimizationType, long tableSize) {
    sendEvent(
        "Optimization Started - Fusion",
        () -> {
          Map<String, Object> props = new HashMap<>(AmoroServiceContainer.getSparkConfig());
          props.put("optimization_type", optimizationTypeHelper(optimizationType));
          props.put("table_size", tableSize);
          return props;
        });
  }

  public void trackOptimizationCompleted(
      OptimizingType optimizationType,
      long tableSize,
      String status,
      long duration,
      int optimizerParallelism) {
    sendEvent(
        "Optimization Completed - Fusion",
        () -> {
          Map<String, Object> props = new HashMap<>(AmoroServiceContainer.getSparkConfig());
          props.put("optimization_type", optimizationTypeHelper(optimizationType));
          props.put("table_size", tableSize);
          props.put("optimization_status", status);
          props.put("duration_ms", duration);
          props.put("optimizer_parallelism", optimizerParallelism);
          return props;
        });
  }

  public void trackCatalogCreated(String catalogType, boolean imported, boolean success) {
    sendEvent("Catalog Created - Fusion", () -> catalogProps(catalogType, imported, success));
  }

  public void trackCatalogUpdated(String catalogType, boolean imported, boolean success) {
    sendEvent("Catalog Updated - Fusion", () -> catalogProps(catalogType, imported, success));
  }

  private Map<String, Object> catalogProps(String catalogType, boolean imported, boolean success) {
    Map<String, Object> props = new HashMap<>();
    props.put("catalog_type", catalogType);
    props.put("imported_from_destination", imported);
    props.put("success", success);
    return props;
  }

  public void trackInstalledFusion(int optimizerParallelism) {
    sendEvent(
        "Installed Fusion",
        () -> {
          Map<String, Object> props = new HashMap<>(AmoroServiceContainer.getSparkConfig());
          props.put("optimizer_parallelism", optimizerParallelism);
          props.put(
              "deployment_mode",
              System.getenv("KUBERNETES_SERVICE_HOST") != null ? "HELM" : "DOCKER");
          return props;
        });
  }
}
