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
import org.apache.logging.log4j.core.util.ContextDataProvider;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// Supplies the "logFilePath" routing key to log events from threads Spark owns, which have no MDC.
// Most precise first: the thread's own MDC, then Spark's per-task mdc.* keys, then this JVM's.
public class AmoroContextDataProvider implements ContextDataProvider {

  @Override
  public Map<String, String> supplyContextData() {
    if (ThreadContext.get(OptimizingTaskLogContext.LOG_FILE_PATH_KEY) != null
        || ThreadContext.get(OptimizingTaskLogContext.PROCESS_ID_KEY) != null
        || ThreadContext.get(OptimizingTaskLogContext.TASK_ID_KEY) != null) {
      return Collections.emptyMap();
    }

    String sparkLogFilePath =
        ThreadContext.get(OptimizerLogContextRegistry.SPARK_LOG_FILE_PATH_KEY);
    if (sparkLogFilePath != null) {
      Map<String, String> data = new HashMap<>(4);
      data.put(OptimizingTaskLogContext.LOG_FILE_PATH_KEY, sparkLogFilePath);
      putIfPresent(
          data,
          OptimizingTaskLogContext.PROCESS_ID_KEY,
          OptimizerLogContextRegistry.SPARK_PROCESS_ID_KEY);
      putIfPresent(
          data,
          OptimizingTaskLogContext.TASK_ID_KEY,
          OptimizerLogContextRegistry.SPARK_TASK_ID_KEY);
      return data;
    }

    OptimizerLogContextRegistry.LogContext current = OptimizerLogContextRegistry.currentFallback();
    return current == null ? Collections.emptyMap() : current.contextData();
  }

  private static void putIfPresent(Map<String, String> data, String key, String sparkKey) {
    String value = ThreadContext.get(sparkKey);
    if (value != null) {
      data.put(key, value);
    }
  }
}
