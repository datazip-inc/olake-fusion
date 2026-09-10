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

import org.apache.amoro.log.OptimizingLogLine;
import org.apache.spark.rpc.RpcEnv;
import org.apache.spark.rpc.ThreadSafeRpcEndpoint;
import scala.PartialFunction;
import scala.runtime.AbstractPartialFunction;
import scala.runtime.BoxedUnit;

/**
 * Driver-side Spark RPC endpoint that receives executor log lines and hands them to {@link
 * OptimizingLogCollector}.
 */
public class SparkOptimizingLogEndpoint implements ThreadSafeRpcEndpoint {

  // FILLER(need-your-change): Spark RPC endpoint name used by executors to look up the driver.
  public static final String ENDPOINT_NAME = "amoro-optimizing-log";

  private final RpcEnv rpcEnv;
  private final OptimizingLogCollector collector;

  public SparkOptimizingLogEndpoint(RpcEnv rpcEnv, OptimizingLogCollector collector) {
    this.rpcEnv = rpcEnv;
    this.collector = collector;
  }

  @Override
  public RpcEnv rpcEnv() {
    return rpcEnv;
  }

  @Override
  public PartialFunction<Object, BoxedUnit> receive() {
    return new AbstractPartialFunction<Object, BoxedUnit>() {
      @Override
      public boolean isDefinedAt(Object message) {
        return message instanceof OptimizingLogLine;
      }

      @Override
      public BoxedUnit apply(Object message) {
        collector.accept((OptimizingLogLine) message);
        return BoxedUnit.UNIT;
      }
    };
  }
}
