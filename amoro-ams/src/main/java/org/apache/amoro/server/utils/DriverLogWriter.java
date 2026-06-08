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

import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes process-level ERROR entries to per-process {@code driver.log} files. */
public final class DriverLogWriter {

  private static final Logger LOG = LoggerFactory.getLogger(DriverLogWriter.class);

  private static final String LOG_BASE_DIR;
  private static final String DRIVER_LOG_FILE = "driver.log";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DateTimeFormatter LOG_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  static {
    String envLogDir = System.getenv("LOG_DIR");
    LOG_BASE_DIR =
        (envLogDir != null && !envLogDir.isEmpty()) ? envLogDir : "/mnt/amoro-logs/compaction";
  }

  private DriverLogWriter() {}

  public static void appendFailReason(long processId, String logger, String failReason) {
    Path driverLogPath = Paths.get(LOG_BASE_DIR, String.valueOf(processId), DRIVER_LOG_FILE);
    try {
      Files.createDirectories(driverLogPath.getParent());
      Map<String, String> logEntry = new LinkedHashMap<>();
      logEntry.put("level", "ERROR");
      logEntry.put("time", LOG_TIME_FORMATTER.format(Instant.now()));
      logEntry.put("processId", String.valueOf(processId));
      logEntry.put("taskId", "");
      logEntry.put("logger", logger);
      logEntry.put("message", failReason);
      logEntry.put("stackTrace", "");
      String logLine = OBJECT_MAPPER.writeValueAsString(logEntry) + System.lineSeparator();
      Files.writeString(
          driverLogPath, logLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception e) {
      LOG.warn("Failed to append fail reason to driver log for process {}", processId, e);
    }
  }
}
