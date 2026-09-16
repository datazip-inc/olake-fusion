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

import org.apache.amoro.optimizer.common.OptimizerConfig;
import org.apache.amoro.optimizer.common.OptimizingLogBatcher;
import org.apache.spark.SparkEnv;

// this and the sparkoptimizinglogendpoint can be in the same file. --ASHI
/** Registers the driver-side log RPC endpoint, collector, and Thrift log batcher. */
public final class SparkOptimizingLogSupport {

  private SparkOptimizingLogSupport() {}

  public static void registerOnDriver(OptimizerConfig config) {
    SparkEnv env = SparkEnv.get();
    if (env == null) {
      throw new IllegalStateException( // throw exception? where will this be caught? --ASHI
          "SparkEnv is not available; cannot register optimizing log endpoint");
    }
    OptimizingLogBatcher batcher = OptimizingLogBatcher.initialize(config);
    OptimizingLogCollector collector = OptimizingLogCollector.initialize(batcher);
    env.rpcEnv()
        .setupEndpoint(
            SparkOptimizingLogEndpoint.ENDPOINT_NAME,
            new SparkOptimizingLogEndpoint(env.rpcEnv(), collector));
    OptimizingTaskRpcLogAppender.install();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(batcher::stopAndFlush, "amoro-optimizing-log-batcher-shutdown"));
  }

  public static void onTokenChange(String token) {
    OptimizingLogBatcher.get().setToken(token);
  }
}
