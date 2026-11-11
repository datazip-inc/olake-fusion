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

import org.apache.amoro.utils.ExecutorMemoryUtil;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs whether executor pods on Kubernetes get the memory overhead they need outside the heap, as a
 * warning when they may be OOMKilled before the executor memory is full. It runs at optimizer
 * startup, for the pod's console, and for each task on the driver, for the run's driver log, which
 * OLake-UI shows.
 */
final class ExecutorOverheadCheck {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutorOverheadCheck.class);

  static final String EXECUTOR_MEMORY = "spark.executor.memory";
  static final String MEMORY_OVERHEAD = "spark.executor.memoryOverhead";
  static final String MEMORY_OVERHEAD_FACTOR = "spark.executor.memoryOverheadFactor";

  private ExecutorOverheadCheck() {}

  static void check(SparkConf conf) {
    // Only Kubernetes puts each executor in a pod with a memory limit
    if (!conf.get("spark.master", "").startsWith("k8s://")) {
      return;
    }
    String executorMemory = conf.get(EXECUTOR_MEMORY, "1g");
    long overheadMb = overheadMb(conf);
    long recommendedMb = ExecutorMemoryUtil.recommendedOverheadMb(executorMemoryMb(conf));
    if (overheadMb < recommendedMb) {
      LOG.warn(
          "Memory overhead may be too low: {} is {}m, and a {} executor needs at least {}m. Set "
              + "{}, or pods may be OOMKilled. To know more: {}",
          MEMORY_OVERHEAD,
          overheadMb,
          executorMemory,
          recommendedMb,
          overheadFix(conf, recommendedMb),
          ExecutorMemoryUtil.MEMORY_OVERHEAD_DOC_URL);
    } else {
      LOG.info(
          "Memory overhead is enough: {} is {}m for a {} executor.",
          MEMORY_OVERHEAD,
          overheadMb,
          executorMemory);
    }
  }

  /**
   * The setting to change. A fixed overhead below 4g, where any factor still gives Spark's 384 MB
   * minimum, and whenever the overhead is set explicitly, because Spark then ignores the factor.
   * Otherwise the factor, so the overhead keeps following the executor memory.
   */
  static String overheadFix(SparkConf conf, long recommendedMb) {
    long executorMb = executorMemoryMb(conf);
    return conf.contains(MEMORY_OVERHEAD) || executorMb < ExecutorMemoryUtil.FIXED_OVERHEAD_BELOW_MB
        ? MEMORY_OVERHEAD + "=" + recommendedMb + "m"
        : MEMORY_OVERHEAD_FACTOR + "=" + ExecutorMemoryUtil.recommendedOverheadFactor(executorMb);
  }

  // Like Spark, a size without a unit is read as MiB
  static long executorMemoryMb(SparkConf conf) {
    return conf.getSizeAsMb(EXECUTOR_MEMORY, "1g");
  }

  static long overheadMb(SparkConf conf) {
    Long explicitOverheadMb =
        conf.contains(MEMORY_OVERHEAD) ? conf.getSizeAsMb(MEMORY_OVERHEAD) : null;
    double factor =
        conf.getDouble(MEMORY_OVERHEAD_FACTOR, ExecutorMemoryUtil.DEFAULT_OVERHEAD_FACTOR);
    return ExecutorMemoryUtil.overheadMb(executorMemoryMb(conf), explicitOverheadMb, factor);
  }
}
