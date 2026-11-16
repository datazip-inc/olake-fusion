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

package org.apache.amoro.optimizer.spark;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** One optimizing log line on its way to AMS, which writes it to the file of its run and task. */
final class LogRecord implements Serializable {
  private static final long serialVersionUID = 1L;

  // Which lines a full buffer drops first: the lowest priority, oldest first.
  static final int PRIORITY_LOW = 0; // TRACE and DEBUG
  static final int PRIORITY_NORMAL = 1; // INFO
  static final int PRIORITY_HIGH = 2; // WARN and above

  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  final long processId;
  // 0 for a process-level line, which AMS writes to driver.log.
  final int taskId;
  final int priority;
  final String ndjson;
  final int bytes;
  // The JVM that logged the line and the line's position in its output, which AMS uses to skip a
  // line it already wrote. A null senderId means AMS writes the line unconditionally.
  String senderId;
  long seq;
  // Position in the buffer of the JVM holding the line now; assigned again on each hop.
  transient long bufferSeq;

  LogRecord(long processId, int taskId, int priority, String ndjson) {
    this.processId = processId;
    this.taskId = taskId;
    this.priority = priority;
    this.ndjson = ndjson;
    this.bytes = ndjson.getBytes(StandardCharsets.UTF_8).length;
  }

  boolean isGapMarker() {
    return senderId == null;
  }

  /** A WARN line that tells the reader of a task log that lines are missing at this point. */
  static LogRecord gapMarker(long processId, int taskId, long droppedLines) {
    String ndjson =
        "{\"level\":\"WARN\",\"time\":\""
            + TIME_FORMAT.format(Instant.now())
            + "\",\"processId\":\""
            + processId
            + "\",\"taskId\":\""
            + (taskId == 0 ? "" : String.valueOf(taskId))
            + "\",\"logger\":\"OptimizingLogShipper\",\"message\":\""
            + droppedLines
            + " log lines are missing here: the optimizer's log buffer was full because AMS was"
            + " unreachable or too slow, so lower-priority lines were dropped\",\"stackTrace\":\"\"}";
    return new LogRecord(processId, taskId, PRIORITY_HIGH, ndjson);
  }
}
