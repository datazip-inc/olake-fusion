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
import org.apache.amoro.optimizer.common.OptimizingLogBuffer;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.spark.SparkEnv;
import org.apache.spark.rpc.RpcAddress;
import org.apache.spark.rpc.RpcEndpointRef;
import org.apache.spark.rpc.RpcTimeout;
import scala.concurrent.duration.FiniteDuration;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executor-side sender of optimizing log events to the driver endpoint.
 *
 * <p>Events wait in a size-capped {@link OptimizingLogBuffer}. A background thread sends them in
 * batches with {@code askSync}; a batch is removed only after the driver acknowledges it, otherwise
 * it goes back to the head of the buffer and is retried on the next flush. Delivery is
 * at-least-once: a batch whose ack times out after the driver accepted it is sent again.
 *
 * <p>Uses Log4j2 {@link StatusLogger} for its own warnings, because this class runs inside a Log4j2
 * appender and must not log through it.
 */
public class SparkOptimizingLogRpcClient {

  private static final StatusLogger STATUS = StatusLogger.getLogger();

  static final long FLUSH_INTERVAL_MS = 200L;
  static final long ASK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
  // Max time a task waits at its end for its lines to reach the driver.
  static final long DRAIN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
  static final long MAX_BATCH_BYTES = 1024L * 1024L;
  static final long MAX_BUFFER_BYTES = 16L * 1024L * 1024L;

  private static final String DRIVER_HOST_KEY = "spark.driver.host";
  private static final String DRIVER_PORT_KEY = "spark.driver.port";
  private static final ClassTag<Boolean> ACK_TAG = ClassTag$.MODULE$.apply(Boolean.class);
  private static final SparkOptimizingLogRpcClient INSTANCE = new SparkOptimizingLogRpcClient();

  private final OptimizingLogBuffer buffer =
      new OptimizingLogBuffer(MAX_BUFFER_BYTES, OptimizingLogEvent.SOURCE_EXECUTOR);
  private final RpcTimeout askTimeout =
      new RpcTimeout(
          FiniteDuration.apply(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
          "amoro.optimizing.log.askTimeout");
  private final ReentrantLock sendLock = new ReentrantLock();
  private final ScheduledExecutorService flushExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "amoro-optimizing-log-rpc-flush");
            thread.setDaemon(true);
            return thread;
          });
  private volatile RpcEndpointRef driverRef;
  // Guarded by sendLock. Used to warn once per outage, not once per flush.
  private boolean failing = false;

  private SparkOptimizingLogRpcClient() {
    flushExecutor.scheduleWithFixedDelay(
        this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> drain(DRAIN_TIMEOUT_MS), "amoro-optimizing-log-rpc-shutdown"));
  }

  public static SparkOptimizingLogRpcClient get() {
    return INSTANCE;
  }

  /** Resolves the driver endpoint once per executor JVM. Safe to call on every task. */
  public void bindDriverEndpoint() {
    if (driverRef != null) {
      return;
    }
    synchronized (this) {
      if (driverRef != null) {
        return;
      }
      SparkEnv env = SparkEnv.get();
      if (env == null) {
        throw new IllegalStateException("SparkEnv is not available");
      }
      String host = env.conf().get(DRIVER_HOST_KEY);
      int port = Integer.parseInt(env.conf().get(DRIVER_PORT_KEY));
      driverRef =
          env.rpcEnv()
              .setupEndpointRef(
                  RpcAddress.apply(host, port), SparkOptimizingLogSupport.ENDPOINT_NAME);
    }
  }

  /** Buffers an event; the flush thread sends it. Never blocks on the network. */
  public void add(OptimizingLogEvent event) {
    buffer.add(event);
  }

  /**
   * Sends buffered events until the buffer is empty, a send fails, or {@code timeoutMs} passes.
   *
   * @return true when the buffer was fully sent
   */
  public boolean drain(long timeoutMs) {
    if (driverRef == null) {
      // Not bound: waiting cannot help, keep lines buffered for a later bind.
      return buffer.isEmpty();
    }
    long deadline = System.currentTimeMillis() + timeoutMs;
    try {
      if (!sendLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
        return false;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    try {
      while (System.currentTimeMillis() < deadline) {
        if (sendAll()) {
          return true;
        }
        Thread.sleep(FLUSH_INTERVAL_MS);
      }
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      sendLock.unlock();
    }
  }

  private void flush() {
    if (sendLock.tryLock()) {
      try {
        sendAll();
      } finally {
        sendLock.unlock();
      }
    }
  }

  private boolean sendAll() {
    RpcEndpointRef ref = driverRef;
    if (ref == null) {
      return buffer.isEmpty();
    }
    while (true) {
      List<OptimizingLogEvent> batch = buffer.drain(MAX_BATCH_BYTES);
      if (batch.isEmpty()) {
        return true;
      }
      try {
        ref.askSync(new SparkOptimizingLogSupport.LogBatch(batch), askTimeout, ACK_TAG);
        if (failing) {
          STATUS.info("Resumed sending optimizing logs to driver");
          failing = false;
        }
      } catch (Throwable t) {
        buffer.requeue(batch);
        if (!failing) {
          STATUS.warn(
              "Failed to send optimizing logs to driver; keeping them buffered: {}",
              t.getMessage());
          failing = true;
        }
        return false;
      }
    }
  }
}
