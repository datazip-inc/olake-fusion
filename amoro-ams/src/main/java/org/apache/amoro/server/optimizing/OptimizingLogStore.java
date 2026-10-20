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
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMS disk store for optimizing logs, and the only writer of them. Layout matches what {@code
 * LogController} reads: {@code LOG_DIR/<processId>/driver.log} and {@code
 * LOG_DIR/<processId>/<taskId>.log}.
 *
 * <p>Lines are written in arrival order. Task id 0 marks a process-level line (task ids start at 1,
 * see {@code OptimizingQueue}), which goes to {@code driver.log}.
 *
 * <p>HA caveat: each AMS node writes to its own {@code LOG_DIR}. With more than one AMS node, the
 * dashboard only sees the logs the serving node received, unless {@code LOG_DIR} is shared or logs
 * move to a central store.
 *
 * <p>AMS does not delete these logs; retention is handled by olake-worker.
 */
public class OptimizingLogStore {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  // Same time format as the optimizer's JSON log lines.
  private static final DateTimeFormatter DRIVER_LOG_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static final String DRIVER_LOG_FILE = "driver.log";
  public static final String DEFAULT_LOG_DIR = "/mnt/amoro-logs/compaction";

  private static final OptimizingLogStore INSTANCE =
      new OptimizingLogStore(envOrDefault("LOG_DIR", DEFAULT_LOG_DIR));

  private final String logBaseDir;
  // driver.log is the only file with more than one writer (the optimizer and AMS itself both
  // append to it); every other file has a single writer. One lock is enough for either case.
  private final Object lock = new Object();

  private OptimizingLogStore(String logBaseDir) {
    this.logBaseDir = logBaseDir;
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

  /**
   * Appends a line written by AMS itself (not by the optimizer) to the process driver.log.
   *
   * @throws UncheckedIOException if the entry cannot be serialized or persisted; we cannot afford
   *     to silently drop a log line, so the caller sees the failure.
   */
  public void appendDriverEntry(long processId, String level, String message, String stackTrace) {
    Map<String, String> logEntry = new LinkedHashMap<>();
    logEntry.put("level", level);
    logEntry.put("time", DRIVER_LOG_TIME_FORMAT.format(Instant.now()));
    logEntry.put("processId", String.valueOf(processId));
    logEntry.put("taskId", "");
    logEntry.put("logger", "");
    logEntry.put("message", message);
    logEntry.put("stackTrace", stackTrace == null ? "" : stackTrace);
    String ndjson;
    try {
      ndjson = OBJECT_MAPPER.writeValueAsString(logEntry);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(
          "Failed to serialize " + level + " entry for driver log of process " + processId, e);
    }
    OptimizingLogLine line = new OptimizingLogLine(new OptimizingTaskId(processId, 0), ndjson);
    append(Collections.singletonList(line));
  }

  /**
   * @throws UncheckedIOException if the lines cannot be persisted; we cannot afford to silently
   *     drop a log line, so the caller sees the failure.
   */
  private void write(Path path, StringBuilder content) {
    synchronized (lock) {
      try {
        Files.createDirectories(path.getParent());
        Files.write(
            path,
            content.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to persist optimizing log lines to " + path, e);
      }
    }
  }

  private Path resolvePath(OptimizingLogLine line) {
    OptimizingTaskId taskId = line.getTaskId();
    Path processDir = Paths.get(logBaseDir, String.valueOf(taskId.getProcessId()));
    if (isDriverLine(line)) {
      return processDir.resolve(DRIVER_LOG_FILE);
    }
    return processDir.resolve(taskId.getTaskId() + ".log");
  }

  // Task ids start at 1 (see OptimizingQueue); taskId is never null, 0 always means a
  // process-level line.
  private static boolean isDriverLine(OptimizingLogLine line) {
    return line.getTaskId().getTaskId() == 0;
  }

  private static String envOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isEmpty() ? defaultValue : value;
  }
}
