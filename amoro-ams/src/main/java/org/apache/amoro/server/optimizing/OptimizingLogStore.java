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
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMS disk store for optimizing logs. Layout matches what {@code LogController} already reads:
 * {@code LOG_DIR/<processId>/driver.log} and {@code LOG_DIR/<processId>/<taskId>.log}.
 */
public class OptimizingLogStore {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizingLogStore.class);
  private static final OptimizingLogStore INSTANCE = new OptimizingLogStore();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public static final String DRIVER_LOG_FILE = "driver.log";
  public static final String DEFAULT_LOG_DIR = "/mnt/amoro-logs/compaction";

  private static final String LOG_BASE_DIR;

  static {
    String envLogDir = System.getenv("LOG_DIR");
    LOG_BASE_DIR = (envLogDir != null && !envLogDir.isEmpty()) ? envLogDir : DEFAULT_LOG_DIR;
  }

  public static OptimizingLogStore get() {
    return INSTANCE;
  }

  public static String getLogBaseDir() {
    return LOG_BASE_DIR;
  }

  public void append(List<OptimizingLogLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return;
    }
    synchronized (this) {
      for (OptimizingLogLine line : lines) {
        appendOne(line);
      }
    }
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
      String ndjson = OBJECT_MAPPER.writeValueAsString(logEntry);
      // FILLER(need-your-change): AMS-as-writer fail-reason lines are not sequenced with the
      // optimizer driver counter; sequence is 0.
      OptimizingLogLine line =
          new OptimizingLogLine(new OptimizingTaskId(processId, 0), ndjson, 0L);
      line.setSource(org.apache.amoro.log.OptimizingLogLine.SOURCE_DRIVER);
      synchronized (this) {
        appendOne(line);
      }
    } catch (Exception e) {
      LOG.warn("Failed to append fail reason to driver log for process {}", processId, e);
    }
  }

  private void appendOne(OptimizingLogLine line) {
    if (line == null || line.getNdjson() == null || line.getNdjson().isEmpty()) {
      return;
    }
    Path path = resolvePath(line);
    try {
      Files.createDirectories(path.getParent());
      String record = line.getNdjson();
      if (!record.endsWith("\n")) {
        record = record + System.lineSeparator();
      }
      Files.writeString(
          path,
          record,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (Exception e) {
      LOG.warn("Failed to persist optimizing log line to {}", path, e);
    }
  }

  private static Path resolvePath(OptimizingLogLine line) {
    OptimizingTaskId taskId = line.getTaskId();
    long processId = taskId == null ? 0L : taskId.getProcessId();
    Path processDir = Paths.get(LOG_BASE_DIR, String.valueOf(processId));
    if (isDriverLine(line)) {
      return processDir.resolve(DRIVER_LOG_FILE);
    }
    return processDir.resolve(taskId.getTaskId() + ".log");
  }

  private static boolean isDriverLine(OptimizingLogLine line) {
    if (org.apache.amoro.log.OptimizingLogLine.SOURCE_DRIVER.equals(line.getSource())) {
      return true;
    }
    return line.getTaskId() == null || line.getTaskId().getTaskId() == 0;
  }
}
