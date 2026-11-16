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

import org.apache.amoro.api.OptimizingTask;
import org.apache.amoro.api.OptimizingTaskResult;
import org.apache.amoro.log.OptimizingTaskLogContext;
import org.apache.amoro.optimizer.common.OptimizerConfig;
import org.apache.amoro.optimizer.common.OptimizerExecutor;
import org.apache.spark.TaskContext;
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

    SparkLogShipping.beforeTask();
    // AMS writes this thread's lines to <processId>/<taskId>.log. The attempt tells apart the lines
    // of a Spark retry of the same task, which go to the same file.
    TaskContext sparkTask = TaskContext.get();
    OptimizingTaskLogContext.setExecutorContext(
        processId, taskId, sparkTask == null ? 0 : sparkTask.attemptNumber());

    OptimizingTaskResult result;
    try {
      result = OptimizerExecutor.executeTask(config, threadId, task, LOG);
    } finally {
      OptimizingTaskLogContext.clearContext();
    }
    // Gives the task's lines a bounded chance to reach the driver before its result does. Never
    // changes the result.
    SparkLogShipping.afterTask();
    return result;
  }
}
