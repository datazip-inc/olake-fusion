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
import java.util.Map;

/**
 * Feeds the Log4j2 routing keys of {@link OptimizerLogContextRegistry} into log events raised by
 * threads that have no {@link ThreadContext} of their own - which is every thread Spark itself
 * owns. Registered through {@code META-INF/services}; Log4j2 merges the map returned here into the
 * context data of each event, which is what the {@code RoutingAppender} keys its per-process and
 * per-task log files on.
 *
 * <p>If the emitting thread already carries any routing key it set up itself, this provider returns
 * nothing: provider data is merged over the ThreadContext map and must not overwrite the more
 * precise attribution that {@link OptimizingTaskLogContext} put there.
 */
public class AmoroContextDataProvider implements ContextDataProvider {

  @Override
  public Map<String, String> supplyContextData() {
    if (ThreadContext.get(OptimizingTaskLogContext.LOG_FILE_PATH_KEY) != null
        || ThreadContext.get(OptimizingTaskLogContext.PROCESS_ID_KEY) != null
        || ThreadContext.get(OptimizingTaskLogContext.TASK_ID_KEY) != null) {
      return Collections.emptyMap();
    }

    OptimizerLogContextRegistry.LogContext current = OptimizerLogContextRegistry.currentFallback();
    return current == null ? Collections.emptyMap() : current.contextData();
  }
}
