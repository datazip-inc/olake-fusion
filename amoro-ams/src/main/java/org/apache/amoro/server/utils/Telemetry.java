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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class Telemetry {
  private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

  private static final String TRACK_URL = "https://analytics.olake.io/mp/track";
  private static final String IPINFO_URL = "https://ipinfo.io/";
  private static final String IPIFY_URL = "https://api.ipify.org?format=text";
  private static final String IP_NOT_FOUND_PLACEHOLDER = "NA";
  private static final String USER_ID_FILE = "user_id.txt";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  private String ipAddress = IP_NOT_FOUND_PLACEHOLDER;
  private String userID;
  private PlatformInfo platform;
  private LocationInfo locationInfo;

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
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.objectMapper = new ObjectMapper();

    initAsync();
  }

  public boolean isTelemetryDisabled() {
    String disabledEnv = System.getenv("TELEMETRY_DISABLED");
    if (disabledEnv != null && Boolean.parseBoolean(disabledEnv)) {
      return true;
    }
    return false;
  }

  private void initAsync() {
    CompletableFuture.runAsync(
        () -> {
          try {
            if (isTelemetryDisabled()) return;
            this.ipAddress = fetchOutboundIP();
            this.userID = resolveUserID();
            this.platform = gatherPlatformInfo();
            this.locationInfo = fetchLocationFromIP(this.ipAddress);
          } catch (Exception e) {
            LOG.debug("Failed to initialize telemetry context safely: {}", e.getMessage());
          }
        });
  }

  private String fetchOutboundIP() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(IPIFY_URL))
              .timeout(Duration.ofSeconds(5))
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
    return IP_NOT_FOUND_PLACEHOLDER;
  }

  private LocationInfo fetchLocationFromIP(String ip) {
    if (IP_NOT_FOUND_PLACEHOLDER.equals(ip)) {
      return new LocationInfo("NA", "NA", "NA");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(IPINFO_URL + ip + "/json"))
              .timeout(Duration.ofSeconds(5))
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
    return new LocationInfo("NA", "NA", "NA");
  }

  private PlatformInfo gatherPlatformInfo() {
    String os = System.getProperty("os.name", "Unknown").toLowerCase();
    String arch = System.getProperty("os.arch", "Unknown");
    int cores = Runtime.getRuntime().availableProcessors();

    return new PlatformInfo(os, arch, cores + " cores");
  }

  private String resolveUserID() {
    String configPathStr = System.getProperty("user.home");
    Path idPath = Paths.get(configPathStr, USER_ID_FILE);

    try {
      if (Files.exists(idPath)) {
        return Files.readString(idPath, StandardCharsets.UTF_8).trim().replace("\"", "");
      }

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String seed = String.valueOf(System.currentTimeMillis());
      byte[] encodedHash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));

      // Convert to Hex String up to 32 characters
      StringBuilder hexString = new StringBuilder();
      for (byte b : encodedHash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      String generatedID = hexString.substring(0, 32);
      Files.writeString(idPath, generatedID, StandardCharsets.UTF_8);
      return generatedID;
    } catch (IOException | NoSuchAlgorithmException e) {
      LOG.debug("Failed to parse or save telemetry user id file, using transient identity.", e);
      return "transient-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
  }

  private void sendEvent(String eventName, Map<String, Object> props) {
    if (isTelemetryDisabled()) return;

    try {
      Map<String, Object> enrichedProperties = new HashMap<>(props);
      enrichedProperties.put("os", platform.os());
      enrichedProperties.put("arch", platform.arch());
      enrichedProperties.put("num_cpu", platform.deviceCpu());
      enrichedProperties.put("ip_address", ipAddress);
      enrichedProperties.put("location", locationInfo);
      enrichedProperties.put("distinct_id", userID);
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
              .timeout(Duration.ofSeconds(5))
              .build();

      // Dispatches entirely non-blocking to protect execution runtime pipelines
      httpClient
          .sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .exceptionally(
              err -> {
                LOG.debug("Async tracking dispatch failed gracefully: {}", err.getMessage());
                return null;
              });

    } catch (Exception e) {
      LOG.debug("Telemetry delivery structure failure: {}", e.getMessage());
    }
  }

  private static final double BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0;

  private static double bytesToGb(long bytes) {
    return bytes / BYTES_PER_GB;
  }

  public String optimizationTypeHelper(String optimizationTypeName) {
    if (optimizationTypeName.equals("MINOR")) return "LITE";
    else if (optimizationTypeName.equals("MAJOR")) return "MEDIUM";
    else return optimizationTypeName;
  }

  public void trackOptimizationStarted(OptimizingType optimizationType, long tableSize) {
    Map<String, Object> props =
        Map.of("optimization_type", 
                optimizationTypeHelper(optimizationType.name()), 
                "table_size", 
                bytesToGb(tableSize));
    sendEvent("Optimization Started - Fusion", props);
  }

  public void trackOptimizationCompleted(
      OptimizingType optimizationType, long tableSize, boolean success) {
    Map<String, Object> props =
        Map.of(
            "Optimization_Type",
            optimizationTypeHelper(optimizationType.name()),
            "table_size",
            bytesToGb(tableSize),
            "Optimization_Status",
            success ? "SUCCESS" : "FAILED");
    sendEvent("Optimization Completed - Fusion", props);
  }

  public void trackCatalogAdded(String catalogType) {
    Map<String, Object> props = Map.of("Catalog_Type", catalogType);
    sendEvent("Catalog Added - Fusion", props);
  }
}
