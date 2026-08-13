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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// Tracks the log file this JVM is working on, so Spark's own MDC-less threads can be attributed.
public final class OptimizerLogContextRegistry {

  public static final String DRIVER_LOG_NAME = "driver";

  // Spark copies job properties named mdc.* into the executor task thread's MDC, prefix included.
  private static final String SPARK_MDC_PREFIX = "mdc.";

  public static final String SPARK_LOG_FILE_PATH_KEY =
      SPARK_MDC_PREFIX + OptimizingTaskLogContext.LOG_FILE_PATH_KEY;

  public static final String SPARK_PROCESS_ID_KEY =
      SPARK_MDC_PREFIX + OptimizingTaskLogContext.PROCESS_ID_KEY;

  public static final String SPARK_TASK_ID_KEY =
      SPARK_MDC_PREFIX + OptimizingTaskLogContext.TASK_ID_KEY;

  private static final Map<Long, LogContext> ACTIVE = new ConcurrentHashMap<>();

  // Read on every log event, written once per task: keep the read a plain volatile read.
  private static volatile LogContext fallback;

  // Survives unbind, so Spark's logging around a task (Running/Finished task) is still attributed.
  private static volatile LogContext lastKnown;

  private OptimizerLogContextRegistry() {}

  // Driver log path, relative to LOG_DIR and without the .log suffix.
  public static String driverLogFilePath(long processId) {
    return processId + "/" + DRIVER_LOG_NAME;
  }

  // Task log path, relative to LOG_DIR and without the .log suffix.
  public static String taskLogFilePath(long processId, int taskId) {
    return processId + "/" + taskId;
  }

  // Claims this JVM for logFilePath; pair with unbind() in a finally. Null taskId means driver.
  public static void bind(long processId, Integer taskId, String logFilePath) {
    LogContext context =
        new LogContext(
            String.valueOf(processId), taskId == null ? null : String.valueOf(taskId), logFilePath);
    ACTIVE.put(Thread.currentThread().getId(), context);
    lastKnown = context;
    recompute();
  }

  // Ends the binding, but leaves it as lastKnown until another task claims the JVM.
  public static void unbind() {
    if (ACTIVE.remove(Thread.currentThread().getId()) != null) {
      recompute();
    }
  }

  // Attribution for a thread with no MDC, or null if none can be determined.
  public static LogContext currentFallback() {
    LogContext active = fallback;
    return active != null ? active : lastKnown;
  }

  // Meaningful only while every binding agrees on one file; ambiguous gives null, so lastKnown wins
  private static synchronized void recompute() {
    LogContext first = null;
    boolean sameFile = true;
    for (LogContext ctx : ACTIVE.values()) {
      if (first == null) {
        first = ctx;
        continue;
      }
      sameFile &= Objects.equals(first.logFilePath, ctx.logFilePath);
    }
    fallback = sameFile ? first : null;
  }

  // Immutable routing keys, pre-rendered as a Log4j2 context data map.
  public static final class LogContext {
    private final String processId;
    private final String taskId;
    private final String logFilePath;
    private final Map<String, String> contextData;

    private LogContext(String processId, String taskId, String logFilePath) {
      this.processId = processId;
      this.taskId = taskId;
      this.logFilePath = logFilePath;

      Map<String, String> data = new HashMap<>(4);
      data.put(OptimizingTaskLogContext.LOG_FILE_PATH_KEY, logFilePath);
      if (processId != null) {
        data.put(OptimizingTaskLogContext.PROCESS_ID_KEY, processId);
      }
      if (taskId != null) {
        data.put(OptimizingTaskLogContext.TASK_ID_KEY, taskId);
      }
      this.contextData = Collections.unmodifiableMap(data);
    }

    public Map<String, String> contextData() {
      return contextData;
    }

    @Override
    public String toString() {
      return "LogContext{processId="
          + processId
          + ", taskId="
          + taskId
          + ", path="
          + logFilePath
          + "}";
    }
  }
}
