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

package io.olake.fusion.optimizer.spark;

import io.olake.fusion.api.OptimizingTask;
import io.olake.fusion.api.OptimizingTaskResult;
import io.olake.fusion.log.OptimizingTaskLogContext;
import io.olake.fusion.optimizer.common.OptimizerConfig;
import io.olake.fusion.optimizer.common.OptimizerExecutor;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code SparkOptimizingTaskExecuteFunction} defines the whole processing logic that how to
 * execute {@code OptimizingTask}
 */
public class SparkOptimizingTaskFunction implements Function<OptimizingTask, OptimizingTaskResult> {
  private static final Logger LOG = LoggerFactory.getLogger(SparkOptimizingTaskFunction.class);
  private final OptimizerConfig config;
  private final int threadId;

  public SparkOptimizingTaskFunction(OptimizerConfig config, int threadId) {
    this.config = config;
    this.threadId = threadId;
  }

  @Override
  public OptimizingTaskResult call(OptimizingTask task) {
    long processId = task.getTaskId().getProcessId();
    int taskId = task.getTaskId().getTaskId();

    SparkOptimizingLogSupport.beforeTask();
    // AMS writes this thread's lines to <processId>/<taskId>.log.
    OptimizingTaskLogContext.setExecutorContext(processId, taskId);

    try {
      return OptimizerExecutor.executeTask(config, threadId, task, LOG);
    } finally {
      OptimizingTaskLogContext.clearContext();
      // Does not wait: the driver waits for the task's lines before it reports the result.
      SparkOptimizingLogSupport.afterExecutorTask(processId, taskId);
    }
  }
}
