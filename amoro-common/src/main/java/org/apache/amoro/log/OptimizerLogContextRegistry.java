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

import org.apache.logging.log4j.ThreadContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which optimizing process/task each thread is currently working on, so that log events
 * emitted by threads that never pass through Amoro code can still be attributed to a compaction.
 *
 * <p>{@link OptimizingTaskLogContext} already covers the thread that runs the task itself: it puts
 * the routing keys into the Log4j2 {@link ThreadContext}, and the {@code RoutingAppender} sends
 * those events to {@code <LOG_DIR>/<processId>/driver.log} or {@code
 * <LOG_DIR>/<processId>/<taskId>.log}. Spark's own log lines, however, come from its internal
 * threads - {@code dag-scheduler-event-loop}, {@code task-result-getter-*}, {@code
 * kubernetes-executor-snapshots-subscribers-*}, {@code dispatcher-event-loop-*}, the block manager
 * and shuffle threads - whose ThreadContext is empty. Those are exactly the lines that explain why
 * a compaction was slow or failed, and without this registry they are dropped from the per-task log
 * files.
 *
 * <p>{@link AmoroContextDataProvider} consults {@link #currentFallback()} for such threads. The
 * attribution rules are deliberately conservative:
 *
 * <ul>
 *   <li>every thread in this JVM is working on the same log file - use it (the common case: the
 *       Spark driver runs the tasks of a single optimizing process, and an executor configured with
 *       {@code spark.executor.cores=1} runs a single task at a time);
 *   <li>otherwise, if they all belong to the same optimizing process - use that process' driver
 *       log, since the line cannot be pinned to one task;
 *   <li>otherwise - no attribution, the line only reaches the console.
 * </ul>
 */
public final class OptimizerLogContextRegistry {

  public static final String DRIVER_LOG_NAME = "driver";

  private static final Map<Long, LogContext> ACTIVE = new ConcurrentHashMap<>();

  /**
   * Attribution used for threads that carry no ThreadContext of their own. Recomputed on bind and
   * unbind (once per task) and read on every single log event, so the read path is a plain volatile
   * read and the pre-built context map is shared rather than rebuilt.
   */
  private static volatile LogContext fallback;

  private OptimizerLogContextRegistry() {}

  /**
   * Log file path (relative to {@code LOG_DIR}, without the {@code .log} suffix) of a driver log.
   */
  public static String driverLogFilePath(long processId) {
    return processId + "/" + DRIVER_LOG_NAME;
  }

  /** Log file path (relative to {@code LOG_DIR}, without the {@code .log} suffix) of a task log. */
  public static String taskLogFilePath(long processId, int taskId) {
    return processId + "/" + taskId;
  }

  /**
   * Marks the calling thread as working on {@code logFilePath}. Must be paired with {@link
   * #unbind()} in a {@code finally} block, otherwise a finished task keeps attracting unrelated
   * Spark log lines.
   *
   * @param taskId may be null for process level (driver) attribution
   */
  public static void bind(long processId, Integer taskId, String logFilePath) {
    ACTIVE.put(
        Thread.currentThread().getId(),
        new LogContext(
            String.valueOf(processId),
            taskId == null ? null : String.valueOf(taskId),
            logFilePath));
    recompute();
  }

  /** Clears the attribution registered by {@link #bind} for the calling thread. */
  public static void unbind() {
    if (ACTIVE.remove(Thread.currentThread().getId()) != null) {
      recompute();
    }
  }

  /** Attribution for a thread with no ThreadContext, or null when it cannot be determined. */
  public static LogContext currentFallback() {
    return fallback;
  }

  private static synchronized void recompute() {
    LogContext first = null;
    boolean sameFile = true;
    boolean sameProcess = true;
    for (LogContext ctx : ACTIVE.values()) {
      if (first == null) {
        first = ctx;
        continue;
      }
      sameFile &= Objects.equals(first.logFilePath, ctx.logFilePath);
      sameProcess &= Objects.equals(first.processId, ctx.processId);
    }

    if (first == null) {
      fallback = null;
    } else if (sameFile) {
      fallback = first;
    } else if (sameProcess) {
      fallback = new LogContext(first.processId, null, first.processId + "/" + DRIVER_LOG_NAME);
    } else {
      fallback = null;
    }
  }

  /** Immutable routing keys for one thread, pre-rendered as a Log4j2 context data map. */
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

    public String logFilePath() {
      return logFilePath;
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
