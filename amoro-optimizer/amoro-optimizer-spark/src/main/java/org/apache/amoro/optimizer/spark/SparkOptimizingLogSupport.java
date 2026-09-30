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

import org.apache.amoro.log.OptimizingLogEvent;
import org.apache.amoro.optimizer.common.OptimizerConfig;
import org.apache.amoro.optimizer.common.OptimizingLogBatcher;
import org.apache.spark.SparkEnv;
import org.apache.spark.rpc.RpcCallContext;
import org.apache.spark.rpc.RpcEnv;
import org.apache.spark.rpc.ThreadSafeRpcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.PartialFunction;
import scala.runtime.AbstractPartialFunction;
import scala.runtime.BoxedUnit;

import java.io.Serializable;
import java.util.List;

/**
 * Driver-side wiring for optimizing log shipping: the Spark RPC endpoint that receives executor log
 * batches, the {@link OptimizingLogCollector}, and the Thrift {@link OptimizingLogBatcher}.
 */
public final class SparkOptimizingLogSupport {

  private static final Logger LOG = LoggerFactory.getLogger(SparkOptimizingLogSupport.class);

  /** Spark RPC endpoint name executors use to look up the driver. */
  public static final String ENDPOINT_NAME = "amoro-optimizing-log";

  private SparkOptimizingLogSupport() {}

  /**
   * Sets up log shipping on the driver. A failure here is logged and the optimizer keeps running:
   * compaction must not stop because logs cannot be shipped. Task logs then go to the console only.
   */
  public static void registerOnDriver(OptimizerConfig config) {
    try {
      SparkEnv env = SparkEnv.get();
      if (env == null) {
        throw new IllegalStateException("SparkEnv is not available");
      }
      OptimizingLogBatcher batcher = OptimizingLogBatcher.initialize(config);
      OptimizingLogCollector collector = OptimizingLogCollector.initialize(batcher);
      env.rpcEnv().setupEndpoint(ENDPOINT_NAME, new LogEndpoint(env.rpcEnv(), collector));
      OptimizingTaskRpcLogAppender.install();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(batcher::stopAndFlush, "amoro-optimizing-log-batcher-shutdown"));
    } catch (Exception e) {
      LOG.warn(
          "Failed to set up optimizing log shipping to AMS; task logs will only go to the console",
          e);
    }
  }

  public static void onTokenChange(String token) {
    if (OptimizingLogBatcher.isInitialized()) {
      OptimizingLogBatcher.get().setToken(token);
    }
  }

  /**
   * Message an executor sends to the driver endpoint. The driver replies {@code true} once
   * accepted.
   */
  public static final class LogBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<OptimizingLogEvent> events;

    public LogBatch(List<OptimizingLogEvent> events) {
      this.events = events;
    }

    public List<OptimizingLogEvent> getEvents() {
      return events;
    }
  }

  /** Driver-side endpoint: hands each executor batch to the collector, then acknowledges it. */
  static final class LogEndpoint implements ThreadSafeRpcEndpoint {
    private final RpcEnv rpcEnv;
    private final OptimizingLogCollector collector;

    LogEndpoint(RpcEnv rpcEnv, OptimizingLogCollector collector) {
      this.rpcEnv = rpcEnv;
      this.collector = collector;
    }

    @Override
    public RpcEnv rpcEnv() {
      return rpcEnv;
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveAndReply(RpcCallContext context) {
      return new AbstractPartialFunction<Object, BoxedUnit>() {
        @Override
        public boolean isDefinedAt(Object message) {
          return message instanceof LogBatch;
        }

        @Override
        public BoxedUnit apply(Object message) {
          try {
            ((LogBatch) message).getEvents().forEach(collector::accept);
            context.reply(Boolean.TRUE);
          } catch (Exception e) {
            context.sendFailure(e);
          }
          return BoxedUnit.UNIT;
        }
      };
    }
  }
}
