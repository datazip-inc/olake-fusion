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

/**
 * One optimizing log event. {@code ndjson} is the existing Log4j2 JSON line (level, time, 
 * processId, taskId, logger, message, stackTrace). {@code sequence} is assigned on the optimizer
 * driver when the line enters {@code OptimizingLogCollector}.
 */
public class OptimizingLogLine implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final String SOURCE_DRIVER = "driver";
  public static final String SOURCE_EXECUTOR = "executor";

  private long processId;
  private int taskId;
  private String ndjson;
  private String source;
  private long sequence;

  public OptimizingLogLine() {}

  public OptimizingLogLine(long processId, int taskId, String ndjson, String source) {
    this.processId = processId;
    this.taskId = taskId;
    this.ndjson = ndjson;
    this.source = source;
  }

  public OptimizingLogLine withSequence(long sequence) {
    OptimizingLogLine copy = new OptimizingLogLine(processId, taskId, ndjson, source);
    copy.sequence = sequence;
    return copy;
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

  public String getSource() {
    return source;
  }

  public long getSequence() {
    return sequence;
  }
}
