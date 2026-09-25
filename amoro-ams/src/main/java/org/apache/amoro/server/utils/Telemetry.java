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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Telemetry {
  private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

  private static final String TRACK_URL = "https://analytics.olake.io/mp/track";
  private static final String IPINFO_URL = "https://ipinfo.io/";
  private static final String IPIFY_URL = "https://api.ipify.org?format=text";
  private static final String NOT_FOUND_PLACEHOLDER = "NA";
  private static final String SHARED_USER_ID_FILE = "/tmp/olake-config/telemetry/user_id";

  private static final long TIMEOUT_SECONDS = 10L;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final CompletableFuture<Void> initFuture;

  private volatile String ipAddress = NOT_FOUND_PLACEHOLDER;
  private volatile PlatformInfo platform;
  private volatile LocationInfo locationInfo;
  private volatile String userID;

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

  private Telemetry() {
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build();
    this.objectMapper = new ObjectMapper();
    this.platform = gatherPlatformInfo();
    this.locationInfo = unknownLocation();
    try {
      if (!isTelemetryDisabled()) {
        this.userID = resolveUserID();
      }
    } catch (Throwable t) {
      LOG.debug("Failed to resolve user ID: {}", t.getMessage());
    }
    this.initFuture = initAsync();
  }

  private static LocationInfo unknownLocation() {
    return new LocationInfo(NOT_FOUND_PLACEHOLDER, NOT_FOUND_PLACEHOLDER, NOT_FOUND_PLACEHOLDER);
  }

  public boolean isTelemetryDisabled() {
    String disabledEnv = System.getenv("TELEMETRY_DISABLED");
    if (disabledEnv != null && Boolean.parseBoolean(disabledEnv)) {
      return true;
    }
    return false;
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
            })
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

  private String resolveUserID() {
    String fromFile = readSharedUserID();
    if (fromFile != null) {
      this.userID = fromFile;
      return fromFile;
    }

    if (this.userID != null) {
      return this.userID;
    }

    // fallback
    String effective = persistUserID(generateUserID());
    this.userID = effective;
    return effective;
  }

  private String readSharedUserID() {
    Path shared = Paths.get(SHARED_USER_ID_FILE);
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

  private String persistUserID(String id) {
    Path shared = Paths.get(SHARED_USER_ID_FILE);
    try {
      Files.createDirectories(shared.getParent());
      Files.writeString(shared, id, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException e) {
      String existing = readSharedUserID();
      if (existing != null) {
        LOG.debug("Adopting telemetry user id already present at {}", shared);
        return existing;
      }
    } catch (IOException e) {
      LOG.debug("Failed to persist generated telemetry user id at {}: {}", shared, e.getMessage());
    }
    return id;
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

  private void sendEvent(String eventName, Map<String, Object> props) {
    if (isTelemetryDisabled()) {
      return;
    }

    try {
      Map<String, Object> snapshot = new HashMap<>(props);
      initFuture.thenRunAsync(() -> dispatchEvent(eventName, snapshot));
    } catch (Throwable t) {
      LOG.debug("Telemetry dispatch scheduling failed: {}", t.getMessage());
    }
  }

  private void dispatchEvent(String eventName, Map<String, Object> props) {
    try {
      PlatformInfo p = platform != null ? platform : gatherPlatformInfo();
      LocationInfo loc = locationInfo != null ? locationInfo : unknownLocation();
      Map<String, Object> enrichedProperties = new HashMap<>(props);
      enrichedProperties.put("os", p.os());
      enrichedProperties.put("arch", p.arch());
      enrichedProperties.put("num_cpu", p.deviceCpu());
      enrichedProperties.put("ip_address", ipAddress);
      enrichedProperties.put("location", loc);
      enrichedProperties.put("distinct_id", userID != null ? userID : resolveUserID());
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

  private String optimizationTypeHelper(String optimizationTypeName) {
    if (optimizationTypeName.equals("MINOR")) {
      return "LITE";
    } else if (optimizationTypeName.equals("MAJOR")) {
      return "MEDIUM";
    } else {
      return optimizationTypeName;
    }
  }

  public void trackOptimizationStarted(OptimizingType optimizationType, long tableSize) {
    Map<String, Object> props = new HashMap<>();
    props.put("optimization_type", optimizationTypeHelper(optimizationType.name()));
    props.put("table_size", tableSize);
    props.putAll(AmoroServiceContainer.getSparkConfig());
    sendEvent("Optimization Started - Fusion", props);
  }

  public void trackOptimizationCompleted(
      OptimizingType optimizationType,
      Long tableSize,
      String status,
      long duration,
      int optimizerParallelism) {
    Map<String, Object> props = new HashMap<>();
    props.put("optimization_type", optimizationTypeHelper(optimizationType.name()));
    props.putAll(AmoroServiceContainer.getSparkConfig());
    props.put("table_size", tableSize);
    props.put("optimization_status", status);
    props.put("duration_ms", duration);
    props.put("optimizer_parallelism", optimizerParallelism);

    sendEvent("Optimization Completed - Fusion", props);
  }

  public void trackCatalogCreated(String catalogType, boolean imported, boolean success) {
    Map<String, Object> props = new HashMap<>();
    props.put("catalog_type", catalogType);
    props.put("imported_from_destination", imported);
    props.put("success", success);
    sendEvent("Catalog Created - Fusion", props);
  }

  public void trackCatalogUpdated(String catalogType, boolean imported, boolean success) {
    Map<String, Object> props = new HashMap<>();
    props.put("catalog_type", catalogType);
    props.put("imported_from_destination", imported);
    props.put("success", success);
    sendEvent("Catalog Updated - Fusion", props);
  }

  public void trackInstalledFusion(int optimizerParallelism) {
    Map<String, Object> props = new HashMap<>();
    props.putAll(AmoroServiceContainer.getSparkConfig());
    props.put("optimizer_parallelism", optimizerParallelism);
    props.put(
        "deployment_mode", System.getenv("KUBERNETES_SERVICE_HOST") != null ? "HELM" : "DOCKER");
    sendEvent("Installed Fusion", props);
  }
}
