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

package org.apache.amoro.log;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * One optimizing log event as it travels from executor to driver (Spark RPC) and from driver to AMS
 * (converted to the Thrift {@code org.apache.amoro.api.OptimizingLogLine}).
 *
 * <p>{@code ndjson} is the Log4j2 JSON line (level, time, processId, taskId, logger, message,
 * stackTrace). AMS picks the target file from {@code taskId}: 0 goes to {@code driver.log}, any
 * other value goes to {@code <taskId>.log}.
 */
public class OptimizingLogEvent implements Serializable {
  private static final long serialVersionUID = 1L;

  private final long processId;
  private final int taskId;
  private final String ndjson;

  public OptimizingLogEvent(long processId, int taskId, String ndjson) {
    this.processId = processId;
    this.taskId = taskId;
    this.ndjson = ndjson;
  }

  public long getProcessId() {
    return processId;
  }

  public int getTaskId() {
    return taskId;
  }

  public String getNdjson() {
    return ndjson;
  }

  public boolean isEmpty() {
    return ndjson == null || ndjson.isEmpty();
  }

  /** Approximate wire size, used for batch splitting and buffer caps. */
  public long sizeInBytes() {
    return ndjson == null ? 0L : ndjson.getBytes(StandardCharsets.UTF_8).length + 1L;
  }
}
