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
import org.apache.amoro.log.OptimizingTaskLogContext;
import org.apache.amoro.server.optimizing.S3LogStore.LogType;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.mapper.TableProcessMapper;
import org.apache.amoro.server.utils.Command;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes optimizing logs to the AMS disk, and is their only writer. Layout matches what {@code
 * LogController} reads: {@code LOG_DIR/<processId>/driver.log} and {@code
 * LOG_DIR/<processId>/<taskId>.log}.
 *
 * <p>Lines are written in arrival order. Task id 0 marks a process-level line (task ids start at 1,
 * see {@code OptimizingQueue}), which goes to {@code driver.log}. Task id {@link
 * OptimizingTaskLogContext#LOST_LINES_TASK_ID} marks a report of lines the optimizer lost: it is
 * written to {@code driver.log} as an ERROR entry and reported in telemetry.
 *
 * <p>HA caveat: each AMS node writes to its own {@code LOG_DIR}. With more than one AMS node, the
 * dashboard only sees the logs the serving node received, unless {@code LOG_DIR} is shared or logs
 * move to a central store.
 *
 * <p>When {@code OLAKE_STORAGE_MODE} is not {@code s3}, AMS does not delete these logs; retention
 * is handled by olake-worker. When it is {@code s3}, each process's files are merged into one
 * object in S3 once the process is completed (see {@link #upload(LogType, long)}), and then
 * deleted.
 */
public class LogWriter extends PersistentBase {

  private static final Logger LOG = LoggerFactory.getLogger(LogWriter.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  // Same time format as the optimizer's JSON log lines.
  private static final DateTimeFormatter DRIVER_LOG_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static final String DRIVER_LOG_FILE = "driver.log";
  private static final String DEFAULT_LOG_DIR = "/mnt/amoro-logs/compaction";
  // A failing disk fails every batch the optimizer sends again; report it at most this often.
  private static final long WRITE_FAILURE_REPORT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);

  private static final LogWriter INSTANCE = new LogWriter(envOrDefault("LOG_DIR", DEFAULT_LOG_DIR));

  private final String logBaseDir;
  // driver.log is the only file with more than one writer (the optimizer and AMS itself both
  // append to it); every other file has a single writer. One lock is enough for either case.
  private final Object lock = new Object();
  // Last time a write failure was reported in telemetry.
  private volatile long lastWriteFailureReport;
  // Null when OLAKE_STORAGE_MODE is not s3.
  private volatile S3LogStore logStore;

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
   * run.
   *
   * @throws UncheckedIOException if the directory cannot be created
   */
  public void createLogDir() {
    try {
      Files.createDirectories(Paths.get(logBaseDir));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create optimizing log directory " + logBaseDir, e);
    }
  }

  /** Sets the S3 store that completed processes' logs are uploaded to; null keeps them local. */
  public void setLogStore(S3LogStore logStore) {
    this.logStore = logStore;
  }

  /**
   * Reads a process's uploaded log from S3, split back into its files: {@code driver.log} and
   * {@code <taskId>.log}, by the taskId of each line. A process in {@code table_process} is a
   * compaction, read from the optimization folder; any other is read from the cleanup folder.
   *
   * @return file name -> content, or null if logs stay local, the process was not uploaded, or S3
   *     cannot be read
   */
  public Map<String, String> readUploaded(String processId) {
    if (logStore == null) {
      return null;
    }
    try {
      long id = Long.parseLong(processId);
      LogType type =
          getAs(TableProcessMapper.class, mapper -> mapper.getProcessMeta(id)) == null
              ? LogType.CLEANUP
              : LogType.OPTIMIZATION;
      byte[] content = logStore.get(type, id);
      return content == null ? null : split(new String(content, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      LOG.warn("Failed to read optimizing logs of process {} from S3", processId, e);
      return null;
    }
  }

  /**
   * Uploads a completed process's logs to S3 as one object (driver.log, then the task logs by task
   * id), then deletes the local files. Call it after the process's final status is in the database
   * and its last AMS line is appended, outside any process lock. Only the files on disk at that
   * point are uploaded. On failure the local files are kept. Does nothing when logs stay local.
   *
   * <p>Lines can still arrive after completion, from tasks that were running when the process
   * failed or was closed. Files are read and deleted under the writers' lock, so no line is read
   * half written, and a file that grew after it was read is kept instead of being deleted.
   */
  public void upload(LogType type, long processId) {
    if (logStore == null) {
      return;
    }
    Path processDir = Paths.get(logBaseDir, String.valueOf(processId));
    try {
      List<Path> files = new ArrayList<>();
      Map<Path, Long> uploadedSizes = new LinkedHashMap<>();
      ByteArrayOutputStream merged = new ByteArrayOutputStream();
      synchronized (lock) {
        if (!Files.isDirectory(processDir)) {
          return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
          stream.forEach(files::add);
        }
        files.sort(Comparator.comparingLong(LogWriter::fileOrder));
        for (Path file : files) {
          byte[] content = Files.readAllBytes(file);
          merged.write(content);
          uploadedSizes.put(file, (long) content.length);
        }
      }
      // Outside the lock, so a slow S3 never blocks writers.
      logStore.put(type, processId, merged.toByteArray());
      synchronized (lock) {
        for (Map.Entry<Path, Long> uploaded : uploadedSizes.entrySet()) {
          if (Files.size(uploaded.getKey()) == uploaded.getValue()) {
            Files.delete(uploaded.getKey());
          }
        }
        boolean linesLeft;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
          linesLeft = stream.iterator().hasNext();
        }
        if (linesLeft) {
          LOG.warn(
              "Optimizing log lines of process {} arrived after its upload; kept in {}",
              processId,
              processDir);
        } else {
          Files.deleteIfExists(processDir);
        }
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn("Failed to upload optimizing logs of process {} to S3", processId, e);
    }
  }

  /** driver.log first (-1), then task logs by task id. */
  private static long fileOrder(Path file) {
    String fileName = file.getFileName().toString();
    return fileName.equals(DRIVER_LOG_FILE)
        ? -1
        : Long.parseLong(fileName.substring(0, fileName.length() - ".log".length()));
  }

  /**
   * Splits a merged log by the taskId of each JSON line: empty goes to driver.log, else to {@code
   * <taskId>.log}. A non-JSON line (a raw stack trace) stays with the line before it.
   */
  private static Map<String, String> split(String merged) {
    Map<String, StringBuilder> contentByFile = new LinkedHashMap<>();
    String fileName = DRIVER_LOG_FILE;
    for (String line : merged.split("\n")) {
      if (line.trim().startsWith("{")) {
        try {
          String taskId = OBJECT_MAPPER.readTree(line).path("taskId").asText("");
          fileName = taskId.isEmpty() ? DRIVER_LOG_FILE : taskId + ".log";
        } catch (JsonProcessingException e) {
          // Not a JSON entry: keep it with the line before it.
        }
      }
      contentByFile.computeIfAbsent(fileName, f -> new StringBuilder()).append(line).append('\n');
    }
    Map<String, String> files = new LinkedHashMap<>();
    contentByFile.forEach((name, content) -> files.put(name, content.toString()));
    return files;
  }

  /**
   * Appends lines from the optimizer, grouped so each target file is opened once per call.
   *
   * @throws UncheckedIOException if the lines cannot be persisted; the optimizer then keeps them
   *     and sends them again
   */
  public void append(List<OptimizingLogLine> lines) {
    Map<Path, StringBuilder> contentByPath = new LinkedHashMap<>();
    List<String> lostLinesReports = new ArrayList<>();
    for (OptimizingLogLine line : lines) {
      OptimizingTaskId taskId = line.getTaskId();
      String ndjson = line.getNdjson();
      if (taskId.getTaskId() == OptimizingTaskLogContext.LOST_LINES_TASK_ID) {
        lostLinesReports.add(ndjson);
        taskId = new OptimizingTaskId(taskId.getProcessId(), 0);
        ndjson = driverEntry(taskId.getProcessId(), "ERROR", ndjson, "");
      }
      contentByPath
          .computeIfAbsent(resolvePath(taskId), path -> new StringBuilder())
          .append(ndjson)
          .append('\n');
    }
    try {
      contentByPath.forEach(this::write);
    } catch (UncheckedIOException e) {
      reportWriteFailure(e);
      throw e;
    }
    for (String report : lostLinesReports) {
      LOG.warn("Optimizer lost optimizing log lines: {}", report);
      Telemetry.getInstance()
          .trackFailure(Command.SHIP_LOGS, "optimizer", new LostLogLinesException(report));
    }
  }

  /**
   * Appends a line written by AMS itself (not by the optimizer) to the process driver.log. Best
   * effort: a failure is logged and reported in telemetry, never thrown, as callers run inside the
   * optimizing state machine. The process's failure reason is also in the database.
   */
  public void appendDriverEntry(long processId, String level, String message, String stackTrace) {
    try {
      OptimizingLogLine line =
          new OptimizingLogLine(
              new OptimizingTaskId(processId, 0),
              driverEntry(processId, level, message, stackTrace));
      writeLine(line);
    } catch (RuntimeException e) {
      LOG.warn("Failed to append {} entry to driver log of process {}", level, processId, e);
      reportWriteFailure(e);
    }
  }

  private void writeLine(OptimizingLogLine line) {
    write(resolvePath(line.getTaskId()), new StringBuilder(line.getNdjson()).append('\n'));
  }

  private static String driverEntry(
      long processId, String level, String message, String stackTrace) {
    Map<String, String> logEntry = new LinkedHashMap<>();
    logEntry.put("level", level);
    logEntry.put("time", DRIVER_LOG_TIME_FORMAT.format(Instant.now()));
    logEntry.put("processId", String.valueOf(processId));
    logEntry.put("taskId", "");
    logEntry.put("logger", "");
    logEntry.put("message", message);
    logEntry.put("stackTrace", stackTrace == null ? "" : stackTrace);
    try {
      return OBJECT_MAPPER.writeValueAsString(logEntry);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(
          "Failed to serialize " + level + " entry for driver log of process " + processId, e);
    }
  }

  private void reportWriteFailure(Throwable error) {
    long now = System.currentTimeMillis();
    if (now - lastWriteFailureReport < WRITE_FAILURE_REPORT_INTERVAL_MS) {
      return;
    }
    lastWriteFailureReport = now;
    Telemetry.getInstance().trackFailure(Command.WRITE_LOGS, "ams", error);
  }

  /** Lines the optimizer could not ship, as it reported them. Telemetry sends its class name. */
  static final class LostLogLinesException extends RuntimeException {
    LostLogLinesException(String message) {
      super(message);
    }
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
