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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes optimizing logs to the AMS disk, and is their only writer. Layout matches what {@code
 * LogController} reads: {@code LOG_DIR/<processId>/driver.log} and {@code
 * LOG_DIR/<processId>/<taskId>.log}.
 *
 * <p>Lines are written in arrival order. Task id 0 marks a process-level line (task ids start at 1,
 * see {@code OptimizingQueue}), which goes to {@code driver.log}.
 *
 * <p>The optimizer sends a batch again when it did not get AMS's confirmation, so a batch can
 * arrive twice. A line that carries a sender id and sequence number is skipped when AMS already
 * wrote a line of that sender with the same or a higher number to the same file.
 *
 * <p>HA caveat: each AMS node writes to its own {@code LOG_DIR}. With more than one AMS node, the
 * dashboard only sees the logs the serving node received, unless {@code LOG_DIR} is shared or logs
 * move to a central store.
 *
 * <p>AMS does not delete these logs; retention is handled by olake-worker.
 */
public class LogWriter {

  private static final Logger LOG = LoggerFactory.getLogger(LogWriter.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  // Same time format as the optimizer's JSON log lines.
  private static final DateTimeFormatter DRIVER_LOG_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static final String DRIVER_LOG_FILE = "driver.log";
  private static final String DEFAULT_LOG_DIR = "/mnt/amoro-logs/compaction";
  // Bounds the memory of the duplicate check. Losing an entry only means a batch sent again for
  // that sender and file may be written twice.
  private static final int MAX_TRACKED_STREAMS = 100_000;

  private static final LogWriter INSTANCE = new LogWriter(envOrDefault("LOG_DIR", DEFAULT_LOG_DIR));

  private final String logBaseDir;
  // Guards the files and writtenSeq. driver.log has two writers (the optimizer and AMS itself).
  private final Object lock = new Object();
  // Highest sequence number written per sender and file, least recently used first.
  private final Map<String, Long> writtenSeq =
      new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
          return size() > MAX_TRACKED_STREAMS;
        }
      };

  private LogWriter(String logBaseDir) {
    this.logBaseDir = logBaseDir;
  }

  public static LogWriter get() {
    return INSTANCE;
  }

  public static String getLogBaseDir() {
    return INSTANCE.logBaseDir;
  }

  /**
   * Creates the log directory at startup, so the dashboard finds it before the first optimizing
   * run. A failure is logged and not thrown: AMS runs without its logs rather than not at all.
   */
  public void createLogDir() {
    try {
      Files.createDirectories(Paths.get(logBaseDir));
    } catch (IOException e) {
      LOG.warn("Failed to create optimizing log directory {}", logBaseDir, e);
    }
  }

  /**
   * Appends lines the optimizer sent, grouped so each target file is opened once per call.
   *
   * @throws UncheckedIOException if a file cannot be written. The optimizer then sends the batch
   *     again; the lines already written are skipped on that retry.
   */
  public void append(List<OptimizingLogLine> lines) {
    synchronized (lock) {
      Map<Path, List<OptimizingLogLine>> linesByPath = new LinkedHashMap<>();
      // Highest sequence number taken in this call, so a line repeated within a batch is skipped
      // too.
      Map<String, Long> takenSeq = new HashMap<>();
      for (OptimizingLogLine line : lines) {
        Path path = resolvePath(line.getTaskId());
        if (line.isSetSenderId() && line.isSetSeq()) {
          String stream = line.getSenderId() + '|' + path;
          long seq = line.getSeq();
          Long written =
              takenSeq.containsKey(stream) ? takenSeq.get(stream) : writtenSeq.get(stream);
          if (written != null && seq <= written) {
            continue;
          }
          takenSeq.put(stream, seq);
        }
        linesByPath.computeIfAbsent(path, p -> new ArrayList<>()).add(line);
      }
      for (Map.Entry<Path, List<OptimizingLogLine>> entry : linesByPath.entrySet()) {
        StringBuilder content = new StringBuilder();
        for (OptimizingLogLine line : entry.getValue()) {
          content.append(line.getNdjson()).append('\n');
        }
        write(entry.getKey(), content);
        // Recorded per file once it is written, so a failure on a later file does not mark this
        // file's lines, or the failed file's lines, wrongly.
        for (OptimizingLogLine line : entry.getValue()) {
          if (line.isSetSenderId() && line.isSetSeq()) {
            writtenSeq.merge(line.getSenderId() + '|' + entry.getKey(), line.getSeq(), Math::max);
          }
        }
      }
    }
  }

  /**
   * Appends a line written by AMS itself (not by the optimizer) to the process driver.log.
   *
   * <p>Best effort: a failure is logged and never thrown. Callers run inside the process state
   * machine, often in a database transaction, which a failed log write must not roll back or
   * interrupt. The message is also kept in the process's fail reason in the database.
   */
  public void appendDriverEntry(long processId, String level, String message, String stackTrace) {
    try {
      Map<String, String> logEntry = new LinkedHashMap<>();
      logEntry.put("level", level);
      logEntry.put("time", DRIVER_LOG_TIME_FORMAT.format(Instant.now()));
      logEntry.put("processId", String.valueOf(processId));
      logEntry.put("taskId", "");
      logEntry.put("logger", "");
      logEntry.put("message", message);
      logEntry.put("stackTrace", stackTrace == null ? "" : stackTrace);
      OptimizingLogLine line =
          new OptimizingLogLine(
              new OptimizingTaskId(processId, 0), OBJECT_MAPPER.writeValueAsString(logEntry));
      append(Collections.singletonList(line));
    } catch (Exception e) {
      LOG.warn("Failed to append {} entry to driver log of process {}", level, processId, e);
    }
  }

  private void write(Path path, StringBuilder content) {
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

  private Path resolvePath(OptimizingTaskId taskId) {
    Path processDir = Paths.get(logBaseDir, String.valueOf(taskId.getProcessId()));
    return processDir.resolve(
        taskId.getTaskId() == 0 ? DRIVER_LOG_FILE : taskId.getTaskId() + ".log");
  }

  private static String envOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isEmpty() ? defaultValue : value;
  }
}
