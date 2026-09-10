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
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.spark.SparkEnv;
import org.apache.spark.rpc.RpcAddress;
import org.apache.spark.rpc.RpcEndpointRef;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executor-side Spark RPC client. Each log line is offered to a local buffer and flushed
 * immediately; if send fails the line stays in the buffer and is retried.
 *
 * <p>FILLER(need-your-change): the retry buffer is unbounded (priority is not to drop a line).
 */
public class SparkOptimizingLogRpcClient {

  private static final StatusLogger STATUS = StatusLogger.getLogger();
  private static final SparkOptimizingLogRpcClient INSTANCE = new SparkOptimizingLogRpcClient();

  // FILLER(need-your-change): how often the executor retries sending buffered lines after RPC
  // failure.
  private static final long RETRY_INTERVAL_MS = 200L;
  private static final String DRIVER_HOST_KEY = "spark.driver.host";
  private static final String DRIVER_PORT_KEY = "spark.driver.port";
  private static final String RPC_RETRY_THREAD_NAME = "amoro-optimizing-log-rpc-retry";

  private final ConcurrentLinkedQueue<OptimizingLogLine> pending = new ConcurrentLinkedQueue<>();
  private final AtomicReference<RpcEndpointRef> driverRef = new AtomicReference<>();
  private final ScheduledExecutorService retryExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, RPC_RETRY_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
          });

  private SparkOptimizingLogRpcClient() {
    retryExecutor.scheduleWithFixedDelay(
        this::flushPending, RETRY_INTERVAL_MS, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  public static SparkOptimizingLogRpcClient get() {
    return INSTANCE;
  }

  /** Resolve the driver log endpoint once per Spark task and cache the ref for subsequent sends. */
  public void bindDriverEndpoint() {
    SparkEnv env = SparkEnv.get();
    if (env == null) {
      STATUS.warn("SparkEnv is not available; cannot bind optimizing log RPC client");
      return;
    }
    String host = env.conf().get(DRIVER_HOST_KEY);
    int port = Integer.parseInt(env.conf().get(DRIVER_PORT_KEY));
    RpcEndpointRef ref =
        env.rpcEnv()
            .setupEndpointRef(
                RpcAddress.apply(host, port), SparkOptimizingLogEndpoint.ENDPOINT_NAME);
    driverRef.set(ref);
    flushPending();
  }

  public void send(OptimizingLogLine line) {
    if (line == null) {
      return;
    }
    pending.add(line);
    flushPending();
  }

  public void flushPending() {
    RpcEndpointRef ref = driverRef.get();
    if (ref == null) {
      return;
    }
    synchronized (this) {
      while (true) {
        OptimizingLogLine head = pending.peek();
        if (head == null) {
          return;
        }
        try {
          ref.send(head);
          pending.poll();
        } catch (Throwable t) {
          STATUS.warn(
              "Failed to send optimizing log line to driver; will retry from local buffer: {}",
              t.getMessage());
          return;
        }
      }
    }
  }
}
