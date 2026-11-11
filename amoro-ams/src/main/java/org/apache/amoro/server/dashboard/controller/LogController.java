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

package org.apache.amoro.server.dashboard.controller;

import io.javalin.http.Context;
import io.javalin.http.HttpCode;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.apache.amoro.server.optimizing.LogChunks;
import org.apache.amoro.server.optimizing.LogWriter;
import org.apache.amoro.server.optimizing.S3LogReader;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogController {
  private static final Logger LOG = LoggerFactory.getLogger(LogController.class);
  private static final String LOG_BASE_DIR = LogWriter.getLogBaseDir();
  private static final String DRIVER_LOG_FILE = LogWriter.DRIVER_LOG_FILE;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // Null in NFS storage mode: logs are read from local disk only.
  private final S3LogReader s3LogReader;

  public LogController(S3LogReader s3LogReader) {
    this.s3LogReader = s3LogReader;
  }

  /**
   * Parses a log file into a list of JSON objects. The file is expected to be NDJSON
   * (newline-delimited JSON), but may also contain raw Java stack trace lines that appear after an
   * ERROR log entry when the log4j2 PatternLayout did not include the throwable inside the JSON
   * pattern. Such raw lines are collected and attached as a {@code stackTrace} field on the
   * preceding log entry so the API can deliver them to the UI.
   *
   * @param logPath Path to the log file
   * @return List of parsed log entry objects (as Maps)
   */
  private List<Map<String, Object>> parseNDJSONLogFile(Path logPath) {
    try (BufferedReader reader = Files.newBufferedReader(logPath)) {
      return parseNDJSON(reader, logPath.getFileName().toString());
    } catch (IOException e) {
      LOG.error("Failed to read log file: {}", logPath, e);
      return new ArrayList<>();
    }
  }

  private List<Map<String, Object>> parseNDJSON(byte[] content, String fileName) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
      return parseNDJSON(reader, fileName);
    } catch (IOException e) {
      LOG.error("Failed to read log file: {}", fileName, e);
      return new ArrayList<>();
    }
  }

  private List<Map<String, Object>> parseNDJSON(BufferedReader reader, String fileName)
      throws IOException {
    List<Map<String, Object>> logEntries = new ArrayList<>();
    String line;
    int lineNumber = 0;
    Map<String, Object> lastJsonEntry = null;
    StringBuilder stackTraceBuffer = new StringBuilder();

    while ((line = reader.readLine()) != null) {
      lineNumber++;
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      if (trimmed.startsWith("{")) {
        // Flush any accumulated raw stack trace lines to the preceding log entry.
        // Only attach if the entry does not already have a non-empty stackTrace field
        // (i.e. it was written by an older log4j2 pattern that did not embed the
        // throwable).
        if (stackTraceBuffer.length() > 0 && lastJsonEntry != null) {
          Object existing = lastJsonEntry.get("stackTrace");
          if (existing == null || existing.toString().isEmpty()) {
            lastJsonEntry.put("stackTrace", stackTraceBuffer.toString().stripTrailing());
          }
          stackTraceBuffer.setLength(0);
        }

        try {
          JsonNode jsonNode = OBJECT_MAPPER.readTree(trimmed);
          Map<String, Object> logEntry = OBJECT_MAPPER.convertValue(jsonNode, Map.class);
          logEntries.add(logEntry);
          lastJsonEntry = logEntry;
        } catch (Exception e) {
          LOG.warn(
              "Failed to parse JSON at line {} in {}: {}", lineNumber, fileName, e.getMessage());
          stackTraceBuffer.append(line).append("\n");
        }
      } else {
        // Non-JSON line: part of a Java stack trace that was written outside the JSON
        // object.
        stackTraceBuffer.append(line).append("\n");
      }
    }

    // Flush any stack trace that trails the last JSON entry (no subsequent JSON
    // line).
    if (stackTraceBuffer.length() > 0 && lastJsonEntry != null) {
      Object existing = lastJsonEntry.get("stackTrace");
      if (existing == null || existing.toString().isEmpty()) {
        lastJsonEntry.put("stackTrace", stackTraceBuffer.toString().stripTrailing());
      }
    }
    return logEntries;
  }

  public void getProcessLogs(Context ctx) {
    String processId = ctx.pathParam("processId");
    if (s3LogReader != null) {
      getProcessLogsFromS3(ctx, processId);
      return;
    }
    Path processDir = Paths.get(LOG_BASE_DIR, processId);

    Map<String, Object> response = new HashMap<>();
    response.put("processId", processId);

    if (!Files.exists(processDir) || !Files.isDirectory(processDir)) {
      response.put("exists", false);
      response.put("message", "Process log directory not found");
      response.put("driverLog", null);
      response.put("taskLogs", new ArrayList<>());
      ctx.json(OkResponse.of(response));
      return;
    }

    response.put("exists", true);

    // reads driver log
    Path driverLogPath = processDir.resolve(DRIVER_LOG_FILE);
    Map<String, Object> driverLog = new HashMap<>();
    if (Files.exists(driverLogPath)) {
      try {
        driverLog.put("exists", true);
        driverLog.put("content", parseNDJSONLogFile(driverLogPath));
      } catch (Exception e) {
        LOG.error("Failed to read driver log: {}", driverLogPath, e);
        driverLog.put("exists", true);
        driverLog.put("error", "Failed to read: " + e.getMessage());
      }
    } else {
      driverLog.put("exists", false);
    }
    response.put("driverLog", driverLog);

    // reads all sub-task logs
    List<Map<String, Object>> taskLogs = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
      for (Path taskLogPath : stream) {
        String fileName = taskLogPath.getFileName().toString();
        if (fileName.equals(DRIVER_LOG_FILE)) {
          continue; // skip driver log
        }

        String taskId = fileName.replace(".log", "");
        Map<String, Object> taskLog = new HashMap<>();
        taskLog.put("taskId", taskId);

        try {
          taskLog.put("exists", true);
          taskLog.put("content", parseNDJSONLogFile(taskLogPath));
          taskLogs.add(taskLog);
        } catch (Exception e) {
          LOG.error("Failed to read task log: {}", taskLogPath, e);
          taskLog.put("exists", true);
          taskLog.put("error", "Failed to read: " + e.getMessage());
          taskLogs.add(taskLog);
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to list task logs in directory: {}", processDir, e);
    }

    response.put("taskLogs", taskLogs);
    ctx.json(OkResponse.of(response));
  }

  public void downloadLogFile(Context ctx) {
    String processId = ctx.pathParam("processId");
    String fileId = ctx.pathParam("fileId");
    if (s3LogReader != null) {
      downloadLogFileFromS3(ctx, processId, fileId);
      return;
    }
    // fileId is either "driver" or a taskId like "1", "2", etc.
    String fileName = fileId + ".log";
    Path logFile = Paths.get(LOG_BASE_DIR, processId, fileName);

    if (!Files.exists(logFile) || !Files.isRegularFile(logFile)) {
      ctx.status(HttpCode.NOT_FOUND).result("Log file not found: " + processId + "/" + fileName);
      return;
    }

    ctx.header("Content-Type", "text/plain; charset=UTF-8");
    ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

    try {
      ctx.result(Files.newInputStream(logFile));
    } catch (IOException e) {
      LOG.error("Failed to read log file: {}", logFile, e);
      ctx.status(HttpCode.INTERNAL_SERVER_ERROR).result("Failed to read log file");
    }
  }

  public void downloadProcessLogs(Context ctx) {
    String processId = ctx.pathParam("processId");
    if (s3LogReader != null) {
      downloadProcessLogsFromS3(ctx, processId);
      return;
    }
    Path processDir = Paths.get(LOG_BASE_DIR, processId);

    if (!Files.exists(processDir) || !Files.isDirectory(processDir)) {
      ctx.status(HttpCode.NOT_FOUND).result("Process log directory not found: " + processId);
      return;
    }

    String tarFileName = "compaction-logs-" + processId + ".tar";
    ctx.header("Content-Type", "application/x-tar");
    ctx.header("Content-Disposition", "attachment; filename=\"" + tarFileName + "\"");

    try {
      OutputStream out = ctx.res.getOutputStream();
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new BufferedOutputStream(out))) {
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(processDir, "*.log")) {
          for (Path logFile : stream) {
            long size = Files.size(logFile);
            TarArchiveEntry entry = new TarArchiveEntry(logFile.getFileName().toString());
            entry.setSize(size);
            tar.putArchiveEntry(entry);
            Files.copy(logFile, tar);
            tar.closeArchiveEntry();
          }
        }

        tar.finish();
      }
    } catch (IOException e) {
      LOG.error("Failed to create tar archive for process {}", processId, e);
    }
  }

  private void getProcessLogsFromS3(Context ctx, String processId) {
    Map<String, Object> response = new HashMap<>();
    response.put("processId", processId);

    Map<String, byte[]> files = isProcessId(processId) ? readAll(processId) : Map.of();
    if (files.isEmpty()) {
      response.put("exists", false);
      response.put("message", "Process log directory not found");
      response.put("driverLog", null);
      response.put("taskLogs", new ArrayList<>());
      ctx.json(OkResponse.of(response));
      return;
    }

    response.put("exists", true);
    Map<String, Object> driverLog = new HashMap<>();
    byte[] driverContent = files.get(LogChunks.DRIVER_FILE_BASE);
    driverLog.put("exists", driverContent != null);
    if (driverContent != null) {
      driverLog.put("content", parseNDJSON(driverContent, DRIVER_LOG_FILE));
    }
    response.put("driverLog", driverLog);

    List<Map<String, Object>> taskLogs = new ArrayList<>();
    files.forEach(
        (fileBase, content) -> {
          if (LogChunks.DRIVER_FILE_BASE.equals(fileBase)) {
            return;
          }
          Map<String, Object> taskLog = new HashMap<>();
          taskLog.put("taskId", fileBase);
          taskLog.put("exists", true);
          taskLog.put("content", parseNDJSON(content, fileBase + ".log"));
          taskLogs.add(taskLog);
        });
    response.put("taskLogs", taskLogs);
    ctx.json(OkResponse.of(response));
  }

  private void downloadLogFileFromS3(Context ctx, String processId, String fileId) {
    String fileName = fileId + ".log";
    if (!isProcessId(processId) || !LogChunks.isValidFileBase(fileId)) {
      ctx.status(HttpCode.BAD_REQUEST).result("Invalid log file: " + processId + "/" + fileName);
      return;
    }
    byte[] content;
    try {
      content = s3LogReader.read(Long.parseLong(processId), fileId);
    } catch (RuntimeException e) {
      LOG.error("Failed to read log file {}/{}", processId, fileName, e);
      ctx.status(HttpCode.INTERNAL_SERVER_ERROR).result("Failed to read log file");
      return;
    }
    if (content == null) {
      ctx.status(HttpCode.NOT_FOUND).result("Log file not found: " + processId + "/" + fileName);
      return;
    }
    ctx.header("Content-Type", "text/plain; charset=UTF-8");
    ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
    ctx.result(content);
  }

  private void downloadProcessLogsFromS3(Context ctx, String processId) {
    Map<String, byte[]> files = isProcessId(processId) ? readAll(processId) : Map.of();
    if (files.isEmpty()) {
      ctx.status(HttpCode.NOT_FOUND).result("Process log directory not found: " + processId);
      return;
    }

    String tarFileName = "compaction-logs-" + processId + ".tar";
    ctx.header("Content-Type", "application/x-tar");
    ctx.header("Content-Disposition", "attachment; filename=\"" + tarFileName + "\"");

    try {
      OutputStream out = ctx.res.getOutputStream();
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new BufferedOutputStream(out))) {
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
          TarArchiveEntry entry = new TarArchiveEntry(file.getKey() + ".log");
          entry.setSize(file.getValue().length);
          tar.putArchiveEntry(entry);
          tar.write(file.getValue());
          tar.closeArchiveEntry();
        }
        tar.finish();
      }
    } catch (IOException e) {
      LOG.error("Failed to create tar archive for process {}", processId, e);
    }
  }

  /**
   * Reads all log files of a process from S3 and local disk. A read failure is logged and shown as
   * no logs, like a missing local directory.
   */
  private Map<String, byte[]> readAll(String processId) {
    try {
      return s3LogReader.readAll(Long.parseLong(processId));
    } catch (RuntimeException e) {
      LOG.error("Failed to read logs of process {}", processId, e);
      return Map.of();
    }
  }

  private static boolean isProcessId(String processId) {
    try {
      Long.parseLong(processId);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
