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
//this and the sparkoptimizinglogendpoint can be in the same file. --ASHI
package org.apache.amoro.optimizer.spark;

import org.apache.spark.SparkEnv;

/** Registers the driver-side log RPC endpoint and installs the Log4j2 RPC/collector appender. */
public final class SparkOptimizingLogSupport {

  private SparkOptimizingLogSupport() {}

  public static void registerOnDriver() {
    SparkEnv env = SparkEnv.get();
    if (env == null) {
      throw new IllegalStateException(      //throw exception? where will this be caught? --ASHI
          "SparkEnv is not available; cannot register optimizing log endpoint");
    }
    OptimizingLogCollector collector = OptimizingLogCollector.initialize();
    env.rpcEnv()
        .setupEndpoint(
            SparkOptimizingLogEndpoint.ENDPOINT_NAME,
            new SparkOptimizingLogEndpoint(env.rpcEnv(), collector));
    OptimizingTaskRpcLogAppender.install();
  }
}
