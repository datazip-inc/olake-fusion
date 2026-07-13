package org.apache.amoro.server.utils;

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

import org.apache.amoro.optimizing.OptimizingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Telemetry {
    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);
    
    private static final String TRACK_URL = "https://analytics.olake.io/mp/track";
    private static final String IP_NOT_FOUND_PLACEHOLDER = "NA";
    private static final String USER_ID_FILE = "user_id.txt";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private String ipAddress = IP_NOT_FOUND_PLACEHOLDER;
    private String userID;
    private PlatformInfo platform;
    private LocationInfo locationInfo;

    // Direct translations of Go structs using Java Records
    public record PlatformInfo(String os, String arch, String version, String deviceCpu) {}

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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        
        // Emulate Go's "go func() { Init() }" by executing concurrently at startup
        initAsync();
    }

    // =========================================================================
    // INFRASTRUCTURE & METADATA UTILITIES (Placed before trackers)
    // =========================================================================

    private void initAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                String disabledEnv = System.getenv("TELEMETRY_DISABLED");
                if (disabledEnv != null && Boolean.parseBoolean(disabledEnv)) {
                    return;
                }

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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org?format=text"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ipinfo.io/" + ip + "/json"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        
        String driverVersion = System.getenv("DRIVER_VERSION");
        if (driverVersion == null || driverVersion.isEmpty()) {
            driverVersion = "Not Available";
        }

        return new PlatformInfo(os, arch, driverVersion, cores + " cores");
    }

    private String resolveUserID() {
        // Attempts to read config location dynamically via system property or defaults to user home
        String configPathStr = System.getProperty("amoro.config.path", System.getProperty("user.home"));
        Path idPath = Paths.get(configPathStr, USER_ID_FILE);

        try {
            if (Files.exists(idPath)) {
                return Files.readString(idPath, StandardCharsets.UTF_8).trim().replace("\"", "");
            }

            // Fallback generation matching Go's crypto/sha256 timestamp hash logic
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String seed = String.valueOf(System.currentTimeMillis());
            byte[] encodedHash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            
            // Convert to Hex String up to 32 characters
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String generatedID = hexString.substring(0, 32);

            // Persist locally
            Files.writeString(idPath, generatedID, StandardCharsets.UTF_8);
            return generatedID;
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.debug("Failed to parse or save telemetry user id file, using transient identity.", e);
            return "transient-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /**
     * Core abstract event transport processing logic matching Go's (t *Telemetry) sendEvent
     */
    private void sendEvent(String eventName, Map<String, Object> props) {
        if (platform == null) {
            return; // Engine initialization was skipped or telemetry is intentionally disabled
        }

        try {
            Map<String, Object> enrichedProperties = new HashMap<>(props);
            enrichedProperties.put("os", platform.os());
            enrichedProperties.put("arch", platform.arch());
            enrichedProperties.put("olake_version", platform.version());
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

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TRACK_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            // Dispatches entirely non-blocking to protect execution runtime pipelines
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(err -> {
                        LOG.debug("Async tracking dispatch failed gracefully: {}", err.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            LOG.debug("Telemetry delivery structure failure: {}", e.getMessage());
        }
    }

    // =========================================================================
    // EXPLICIT TRACKER TRIGGERS
    // =========================================================================

    public void trackCompactionStarted(OptimizingType compactionType, long tableSize) {
        CompletableFuture.runAsync(() -> {
            Map<String, Object> props = Map.of(
                    "compaction_type", compactionType.name(),
                    "table_size", tableSize);
            sendEvent("compaction_started", props);
        });
    }

    public void trackCompactionCompleted(
            OptimizingType compactionType, long tableSize, boolean success) {
        CompletableFuture.runAsync(() -> {
            Map<String, Object> props = Map.of(
                    "compaction_type", compactionType.name(),
                    "table_size", tableSize,
                    "compaction_status", success ? "SUCCESS" : "FAILED");
            sendEvent("compaction_completed", props);
        });
    }

    public void trackCatalogAdded(String catalogType) {
        CompletableFuture.runAsync(() -> {
            Map<String, Object> props = Map.of(
                    "catalog_type", catalogType);
            sendEvent("catalog_added", props);
        });
    }

    // public void trackDiscover(int streamCount, String sourceType) {
    //     CompletableFuture.runAsync(() -> {
    //         Map<String, Object> props = Map.of(
    //                 "stream_count", streamCount,
    //                 "source_type", sourceType
    //         );
    //         sendEvent("Discover - CLI", props);
    //     });
    // }

    // public void trackSyncStarted(
    //         String syncID, 
    //         int totalStreams, 
    //         int selectedCount, 
    //         int fullLoadCount, 
    //         int cdcCount, 
    //         String sourceType, 
    //         String destinationType, 
    //         String catalogType, 
    //         int normalizedStreams, 
    //         int partitionedStreams) {
        
    //     CompletableFuture.runAsync(() -> {
    //         Map<String, Object> props = new HashMap<>();
    //         props.put("sync_start", System.currentTimeMillis() / 1000L);
    //         props.put("sync_id", syncID);
    //         props.put("stream_count", totalStreams);
    //         props.put("selected_count", selectedCount);
    //         props.put("full_load_streams", fullLoadCount);
    //         props.put("cdc_streams", cdcCount);
    //         props.put("source_type", sourceType);
    //         props.put("destination_type", destinationType);
    //         props.put("catalog_type", catalogType);
    //         props.put("normalized_streams", normalizedStreams);
    //         props.put("partitioned_streams", partitionedStreams);

    //         sendEvent("Sync Started - CLI", props);
    //     });
    // }

    // public void trackSyncCompleted(String syncID, boolean status, long recordsSynced) {
    //     CompletableFuture.runAsync(() -> {
    //         Map<String, Object> props = Map.of(
    //                 "sync_id", syncID,
    //                 "sync_end", System.currentTimeMillis() / 1000L,
    //                 "sync_status", status ? "SUCCESS" : "FAILED",
    //                 "records_synced", recordsSynced
    //         );
    //         sendEvent("Sync Completed - CLI", props);
    //     });
    // }
}
