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

import org.apache.amoro.OptimizerProperties;
import org.apache.amoro.optimizing.OptimizingType;
import org.apache.amoro.server.AmoroServiceContainer;
import org.apache.amoro.server.persistence.PlatformPropertyStore;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * it, pushes it to AMS, and AMS keeps it in {@code platform_property}; telemetry reads it from
 * there and reports nothing under any other id.
 */
public class Telemetry {
  private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

  private static final String TRACK_URL = "https://analytics.olake.io/mp/track";
  private static final String IPINFO_URL = "https://ipinfo.io/json";
  private static final String NOT_FOUND_PLACEHOLDER = "NA";

  private static final String TELEMETRY_DISABLED_ENV = "TELEMETRY_DISABLED";
  /** Set in the Fusion and Spark optimizer images to the release tag they were built for. */
  private static final String FUSION_VERSION_ENV = "FUSION_VERSION";

  /** The release this AMS was built for, NA outside a release image. */
  private static final String FUSION_VERSION = orPlaceholder(System.getenv(FUSION_VERSION_ENV));

  /** The release the optimizer reported when it registered, NA until one has. */
  private static volatile String optimizerVersion = NOT_FOUND_PLACEHOLDER;

  private static final long TIMEOUT_SECONDS = 10L;
  /** Bounded so that a slow or unreachable collector can never accumulate work. */
  private static final int MAX_PENDING_EVENTS = 256;

  private static final double BYTES_PER_GB = 1024d * 1024d * 1024d;

  /**
   * Every failure is sent as this one event, with the operation in its {@code command} property, as
   * the connector sends "Failure - CLI".
   */
  static final String FAILURE_EVENT = "Failure - Fusion";

  /**
   * An operation whose failures are reported. Failure telemetry follows the OLake connector's
   * TrackFailure (olake utils/telemetry) and its categories (olake utils/errs). Enum names are sent
   * lower-cased: CREATE_CATALOG -> create_catalog.
   */
  public enum Command {
    CREATE_CATALOG,
    UPDATE_CATALOG,
    DROP_CATALOG,
    // Building a stored catalog when AMS starts.
    LOAD_CATALOG,
    // Table sync, run every explore interval.
    EXPLORE_CATALOG,
    LIST_TABLES,
    SYNC_TABLE,
    DISPOSE_TABLE,
    // Launching an optimizer in its container; the source is the container type, e.g. flink.
    START_OPTIMIZER,
    // A started optimizer registering with AMS.
    REGISTER_OPTIMIZER,
    // Optimizer bookkeeping in AMS, retried on a loop.
    KEEP_OPTIMIZERS,
    RETRY_OPTIMIZING_TASK,
    SYNC_OPTIMIZER_GROUPS
  }

  /**
   * Each category lists the exception simple class names it covers, as olake
   * destination/iceberg/errors.go. Names, not classes: most come from catalog clients that are not
   * on the AMS classpath.
   */
  enum Category {
    DNS_RESOLUTION_FAILED("UnknownHostException"),
    NETWORK_UNREACHABLE(
        "ConnectException",
        "NoRouteToHostException",
        "HttpHostConnectException",
        "NoHttpResponseException",
        "ConnectionClosedException",
        "SocketException",
        "TTransportException",
        "MetaException",
        "RuntimeMetaException",
        "ServiceUnavailableException",
        "InternalServiceException",
        "SQLTransientConnectionException",
        "SQLNonTransientConnectionException"),
    TIMEOUT(
        "SocketTimeoutException",
        "ConnectTimeoutException",
        "OperationTimeoutException",
        "SQLTimeoutException"),
    TLS_FAILED("SSLHandshakeException", "CertificateException", "SSLPeerUnverifiedException"),
    AUTH_FAILED(
        "NotAuthorizedException",
        "SaslException",
        "LoginException",
        "GSSException",
        "GoogleAuthException",
        "TokenResponseException",
        "SQLInvalidAuthorizationSpecException"),
    PERMISSION_DENIED(
        "ForbiddenException",
        "AccessDeniedException",
        "AccessControlException",
        "GlueEncryptionException"),
    OBJECT_NOT_FOUND(
        "NoSuchTableException",
        "NoSuchNamespaceException",
        "NoSuchViewException",
        "EntityNotFoundException",
        "NoSuchKeyException",
        "NoSuchWarehouseException",
        "NoSuchObjectException",
        "UnknownDBException",
        "NotFoundException",
        "NoSuchIcebergTableException",
        "NoSuchIcebergViewException"),
    CONFIG_INVALID("BadRequestException", "UnprocessableEntityException", "InvalidInputException"),
    CATALOG_ERROR(
        "AlreadyExistsException",
        "CommitStateUnknownException",
        "ServiceFailureException",
        "RESTException",
        "NamespaceNotEmptyException",
        "InvalidObjectException",
        "InvalidOperationException",
        "TException",
        "TApplicationException",
        "UncheckedSQLException",
        "GoogleJsonResponseException",
        "SQLIntegrityConstraintViolationException"),
    UNSUPPORTED_FEATURE("UnsupportedOperationException", "SQLFeatureNotSupportedException"),
    INTERNAL_ERROR(
        "NoClassDefFoundError",
        "ClassNotFoundException",
        "NullPointerException",
        "AssertionError",
        "IllegalArgumentException",
        "IllegalStateException"),
    /** Fallback for failures not mapped above, as olake errs.Unclassified. */
    UNCLASSIFIED;

    private final String[] exceptions;

    Category(String... exceptions) {
      this.exceptions = exceptions;
    }
  }

  /** Exception simple class name to category. */
  private static final Map<String, Category> FAILURE_CATEGORIES = new HashMap<>();

  static {
    for (Category category : Category.values()) {
      for (String exception : category.exceptions) {
        FAILURE_CATEGORIES.put(exception, category);
      }
    }
  }

  /** Set from the AMS configuration; {@code null} means "not configured, fall back to the env". */
  private static volatile Boolean configuredDisabled;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Executor executor;
  private final CompletableFuture<Void> initFuture;

  private volatile String ipAddress = NOT_FOUND_PLACEHOLDER;
  private final PlatformInfo platform = gatherPlatformInfo();
  private volatile LocationInfo locationInfo = unknownLocation();
  /** The install id, read from {@code platform_property} or pushed by the OLake UI. */
  private volatile String userID;

  private final PlatformPropertyStore propertyStore = new PlatformPropertyStore();

  public record PlatformInfo(String os, String arch, String deviceCpu) {}

  public record LocationInfo(String country, String region, String city) {}

  /** The ipinfo.io response: the outbound IP and the location it maps to, in one call. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record IpInfo(String ip, String country, String region, String city) {}

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
    return new ThreadPoolExecutor(
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
  }

  private static String orPlaceholder(String value) {
    return value == null || value.isEmpty() ? NOT_FOUND_PLACEHOLDER : value;
  }

  private static LocationInfo unknownLocation() {
    return new LocationInfo(NOT_FOUND_PLACEHOLDER, NOT_FOUND_PLACEHOLDER, NOT_FOUND_PLACEHOLDER);
  }

  public boolean isTelemetryDisabled() {
    return Boolean.parseBoolean(System.getenv(TELEMETRY_DISABLED_ENV))
        || Boolean.TRUE.equals(configuredDisabled);
  }

  private CompletableFuture<Void> initAsync() {
    return CompletableFuture.runAsync(
            () -> {
              try {
                if (isTelemetryDisabled()) {
                  return;
                }
                fetchIpContext();
              } catch (Throwable t) {
                LOG.debug("Failed to initialize telemetry context: {}", t.getMessage());
              }
            },
            executor)
        .completeOnTimeout(null, 2 * TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** Fills in {@link #ipAddress} and {@link #locationInfo}; leaves both unknown on any failure. */
  private void fetchIpContext() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(IPINFO_URL))
              .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return;
      }
      IpInfo info = objectMapper.readValue(response.body(), IpInfo.class);
      if (info.ip() != null && !info.ip().isEmpty()) {
        this.ipAddress = info.ip().trim();
      }
      this.locationInfo =
          new LocationInfo(
              orPlaceholder(info.country()),
              orPlaceholder(info.region()),
              orPlaceholder(info.city()));
    } catch (Exception e) {
      LOG.debug("Failed to fetch IP and location context: {}", e.getMessage());
    }
  }

  private static PlatformInfo gatherPlatformInfo() {
    String os = System.getProperty("os.name", "Unknown").toLowerCase();
    String arch = System.getProperty("os.arch", "Unknown");
    int cores = Runtime.getRuntime().availableProcessors();

    return new PlatformInfo(os, arch, cores + " cores");
  }

  /**
   * The install id, read from {@code platform_property} and cached for the life of the process.
   * {@link #NOT_FOUND_PLACEHOLDER} while the OLake UI has not pushed one yet.
   */
  private String resolveUserID() {
    String cached = this.userID;
    if (cached != null) {
      return cached;
    }
    try {
      String stored = propertyStore.get(PlatformPropertyStore.TELEMETRY_INSTALL_ID);
      if (stored != null && !stored.isEmpty()) {
        this.userID = stored;
        return stored;
      }
      LOG.debug("No OLake install id stored yet, reporting without one");
    } catch (Throwable t) {
      LOG.debug("Failed to read telemetry install id from the database: {}", t.getMessage());
    }
    return NOT_FOUND_PLACEHOLDER;
  }

  /**
   * Reports under an install id the caller has already validated and stored in {@code
   * platform_property}. The OLake UI owns this id.
   */
  public void useInstallId(String id) {
    if (id == null || id.isEmpty()) {
      return;
    }
    this.userID = id;
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
    String distinctId = resolveUserID();
    try {
      if (httpClient == null || objectMapper == null) {
        LOG.debug(
            "Telemetry dispatch skipped for {}: HTTP client or object mapper is not initialized",
            eventName);
        return;
      } else if (distinctId == NOT_FOUND_PLACEHOLDER) {
        LOG.debug("Telemetry dispatch skipped for {}: distinct_id not found", eventName);
        return;
      }
      Map<String, Object> enrichedProperties = new HashMap<>(props);
      enrichedProperties.put("os", platform.os());
      enrichedProperties.put("arch", platform.arch());
      enrichedProperties.put("num_cpu", platform.deviceCpu());
      enrichedProperties.put("ip_address", ipAddress);
      enrichedProperties.put("location", locationInfo);
      enrichedProperties.put("distinct_id", distinctId);
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

  /** Converts a byte count to gibibytes (1024^3), rounded to 3 decimal places. */
  private static double bytesToGb(long bytes) {
    return Math.round(bytes / BYTES_PER_GB * 1000d) / 1000d;
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
          props.put("table_size (GB)", bytesToGb(tableSize));
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
          props.put("table_size (GB)", bytesToGb(tableSize));
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

  /** Keeps the version a registering optimizer reports, for the failures reported after it. */
  public static void updateOptimizerVersion(Map<String, String> optimizerProperties) {
    if (optimizerProperties != null) {
      optimizerVersion =
          orPlaceholder(optimizerProperties.get(OptimizerProperties.OPTIMIZER_VERSION));
    }
  }

  /**
   * Reports why an operation failed as a classification, never a message, like the connector's
   * TrackFailure. Unmapped failures are sent with the UNCLASSIFIED category.
   */
  public void trackFailure(Command command, String errorSource, Throwable error) {
    try {
      Map<String, Object> props = failureProps(command, errorSource, error);
      if (props != null) {
        sendEvent(FAILURE_EVENT, () -> props);
      }
    } catch (Throwable t) {
      LOG.debug("Failed to report failure for {}: {}", command, t.getMessage());
    }
  }

  static Map<String, Object> failureProps(Command command, String errorSource, Throwable error) {
    if (error == null) {
      return null;
    }
    // Root first, so the most specific mapped cause wins over a broader wrapper above it.
    List<Throwable> chain = ExceptionUtils.getThrowableList(error);
    Collections.reverse(chain);
    Category category =
        chain.stream()
            .map(Telemetry::categoryOf)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(Category.UNCLASSIFIED);

    Map<String, Object> props = new HashMap<>();
    props.put("command", wire(command));
    props.put("error_source", errorSource);
    props.put("category", wire(category));
    props.put("fusion_version", FUSION_VERSION);
    props.put("optimizer_version", optimizerVersion);
    if (category == Category.UNCLASSIFIED) {
      props.put("error_type", chain.get(0).getClass().getName());
    }
    return props;
  }

  /** The category of the exception's class, or of the nearest superclass that has one. */
  private static Category categoryOf(Throwable t) {
    for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
      Category category = FAILURE_CATEGORIES.get(c.getSimpleName());
      if (category != null) {
        return category;
      }
    }
    return null;
  }

  private static String wire(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
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
