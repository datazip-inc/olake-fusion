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

package org.apache.amoro.server.optimizing;

import org.apache.amoro.api.OptimizingLogLine;
import org.apache.amoro.api.OptimizingTaskId;
import org.apache.amoro.log.OptimizingLogEvent;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * AMS disk store for optimizing logs, and the only writer of them. Layout matches what {@code
 * LogController} reads: {@code LOG_DIR/<processId>/driver.log} and {@code
 * LOG_DIR/<processId>/<taskId>.log}.
 *
 * <p>Lines are written in arrival order; the optimizer's {@code sequence} is not used yet.
 *
 * <p>HA caveat: each AMS node writes to its own {@code LOG_DIR}. With more than one AMS node, the
 * dashboard only sees the logs the serving node received, unless {@code LOG_DIR} is shared or logs
 * move to a central store.
 *
 * <p>Retention: a process directory with no file modified for {@code LOG_RETENTION_DAYS} (env,
 * default 7, 0 or less disables) is deleted by an hourly task.
 */
public class OptimizingLogStore {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizingLogStore.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public static final String DRIVER_LOG_FILE = "driver.log";
  public static final String DEFAULT_LOG_DIR = "/mnt/amoro-logs/compaction";
  static final int DEFAULT_RETENTION_DAYS = 7;
  private static final long RETENTION_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
  // Striped locks: writes to different files proceed in parallel, same-file writes stay whole.
  private static final int LOCK_STRIPES = 64;

  private static final OptimizingLogStore INSTANCE =
      new OptimizingLogStore(envOrDefault("LOG_DIR", DEFAULT_LOG_DIR), retentionDaysFromEnv());

  private final String logBaseDir;
  private final long retentionMs;
  private final Object[] locks = new Object[LOCK_STRIPES];
  private ScheduledExecutorService retentionExecutor;

  private OptimizingLogStore(String logBaseDir, int retentionDays) {
    this.logBaseDir = logBaseDir;
    this.retentionMs = TimeUnit.DAYS.toMillis(Math.max(0, retentionDays));
    for (int i = 0; i < LOCK_STRIPES; i++) {
      locks[i] = new Object();
    }
  }

  public static OptimizingLogStore get() {
    return INSTANCE;
  }

  public static String getLogBaseDir() {
    return INSTANCE.logBaseDir;
  }

  /** Appends lines, grouped so each target file is opened once per call. */
  public void append(List<OptimizingLogLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return;
    }
    Map<Path, StringBuilder> contentByPath = new LinkedHashMap<>();
    for (OptimizingLogLine line : lines) {
      if (line == null || line.getNdjson() == null || line.getNdjson().isEmpty()) {
        continue;
      }
      StringBuilder content =
          contentByPath.computeIfAbsent(resolvePath(line), p -> new StringBuilder());
      content.append(line.getNdjson());
      if (!line.getNdjson().endsWith("\n")) {
        content.append(System.lineSeparator());
      }
    }
    contentByPath.forEach(this::write);
  }

  public void appendDriverFailReason(long processId, String failedReason) {
    String time =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    try {
      Map<String, String> logEntry = new LinkedHashMap<>();
      logEntry.put("level", "ERROR");
      logEntry.put("time", time);
      logEntry.put("processId", String.valueOf(processId));
      logEntry.put("taskId", "");
      logEntry.put("logger", "");
      logEntry.put("message", failedReason);
      logEntry.put("stackTrace", "");
      // Written by AMS itself, outside the optimizer's per-process sequence, so sequence is 0.
      OptimizingLogLine line =
          new OptimizingLogLine(
              new OptimizingTaskId(processId, 0), OBJECT_MAPPER.writeValueAsString(logEntry), 0L);
      line.setSource(OptimizingLogEvent.SOURCE_DRIVER);
      append(Collections.singletonList(line));
    } catch (Exception e) {
      LOG.warn("Failed to append fail reason to driver log for process {}", processId, e);
    }
  }

  public synchronized void startRetention() {
    if (retentionMs <= 0 || retentionExecutor != null) {
      return;
    }
    retentionExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "amoro-optimizing-log-retention");
              thread.setDaemon(true);
              return thread;
            });
    retentionExecutor.scheduleWithFixedDelay(
        this::deleteExpired, 0, RETENTION_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  public synchronized void stopRetention() {
    if (retentionExecutor != null) {
      retentionExecutor.shutdownNow();
      retentionExecutor = null;
    }
  }

  private void deleteExpired() {
    Path baseDir = Paths.get(logBaseDir);
    if (!Files.isDirectory(baseDir)) {
      return;
    }
    long cutoff = System.currentTimeMillis() - retentionMs;
    try (DirectoryStream<Path> processDirs =
        Files.newDirectoryStream(baseDir, Files::isDirectory)) {
      for (Path processDir : processDirs) {
        if (lastModified(processDir) < cutoff) {
          deleteRecursively(processDir);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to clean up expired optimizing logs under {}", baseDir, e);
    }
  }

  private void write(Path path, StringBuilder content) {
    synchronized (locks[Math.floorMod(path.hashCode(), LOCK_STRIPES)]) {
      try {
        Files.createDirectories(path.getParent());
        Files.write(
            path,
            content.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (Exception e) {
        LOG.warn("Failed to persist optimizing log lines to {}", path, e);
      }
    }
  }

  private Path resolvePath(OptimizingLogLine line) {
    OptimizingTaskId taskId = line.getTaskId();
    long processId = taskId == null ? 0L : taskId.getProcessId();
    Path processDir = Paths.get(logBaseDir, String.valueOf(processId));
    if (isDriverLine(line)) {
      return processDir.resolve(DRIVER_LOG_FILE);
    }
    return processDir.resolve(taskId.getTaskId() + ".log");
  }

  // Task ids start at 1 (see OptimizingQueue), so taskId 0 always means a process-level line.
  private static boolean isDriverLine(OptimizingLogLine line) {
    return OptimizingLogEvent.SOURCE_DRIVER.equals(line.getSource())
        || line.getTaskId() == null
        || line.getTaskId().getTaskId() == 0;
  }

  private static long lastModified(Path dir) throws IOException {
    long latest = Files.getLastModifiedTime(dir).toMillis();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis());
      }
    }
    return latest;
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
    LOG.info("Deleted expired optimizing logs {}", dir);
  }

  private static String envOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isEmpty() ? defaultValue : value;
  }

  private static int retentionDaysFromEnv() {
    String value = System.getenv("LOG_RETENTION_DAYS");
    if (value == null || value.isEmpty()) {
      return DEFAULT_RETENTION_DAYS;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      LOG.warn("Invalid LOG_RETENTION_DAYS '{}', using {}", value, DEFAULT_RETENTION_DAYS);
      return DEFAULT_RETENTION_DAYS;
    }
  }
}
