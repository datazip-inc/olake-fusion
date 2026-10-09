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

// manages per-thread logging context using Log4j2 thread context: "processId", "taskId" and
// "logChannel"
public class OptimizingTaskLogContext {

  public static final String PROCESS_ID_KEY = "processId";
  public static final String TASK_ID_KEY = "taskId";
  // Set by the Spark optimizer only: marks an event for shipping to AMS and says which side of the
  // job produced it.
  public static final String LOG_CHANNEL_KEY = "logChannel";
  public static final String LOG_CHANNEL_DRIVER = "driver";
  public static final String LOG_CHANNEL_EXECUTOR = "executor";

  // Driver lines carry no taskId, so AMS writes them to the process driver.log.
  public static void setDriverContext(long processId) {
    ThreadContext.put(LOG_CHANNEL_KEY, LOG_CHANNEL_DRIVER);
    ThreadContext.put(PROCESS_ID_KEY, String.valueOf(processId));
  }

  public static void setExecutorContext(long processId, int taskId) {
    ThreadContext.put(LOG_CHANNEL_KEY, LOG_CHANNEL_EXECUTOR);
    ThreadContext.put(PROCESS_ID_KEY, String.valueOf(processId));
    ThreadContext.put(TASK_ID_KEY, String.valueOf(taskId));
  }

  // Removes every key the setters put, so a reused thread does not ship stale context.
  public static void clearContext() {
    ThreadContext.remove(LOG_CHANNEL_KEY);
    ThreadContext.remove(PROCESS_ID_KEY);
    ThreadContext.remove(TASK_ID_KEY);
  }
}
