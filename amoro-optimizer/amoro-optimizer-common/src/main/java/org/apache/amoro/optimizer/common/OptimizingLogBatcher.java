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

package org.apache.amoro.optimizer.common;

import org.apache.amoro.api.OptimizingLogLine;
import org.apache.amoro.api.OptimizingTaskId;
import org.apache.amoro.client.OptimizingClientPools;
import org.apache.amoro.log.OptimizingLogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sends sequenced optimizer log events to AMS through {@code OptimizingService.appendLogs}.
 *
 * <p>Events wait in a size-capped {@link OptimizingLogBuffer} and are flushed every {@link
 * #FLUSH_INTERVAL_MS}, or sooner when a full batch is buffered. Each flush makes one attempt per
 * batch; a failed batch goes back to the head of the buffer and the next scheduled flush is the
 * retry. There is no inner retry loop, so a flush never blocks longer than one Thrift call.
 */
public class OptimizingLogBatcher extends AbstractOptimizerOperator {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizingLogBatcher.class);

  static final long FLUSH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);
  // Max payload of one appendLogs call. Must stay well below AMS thrift-server.max-message-size
  // (100 MB by default).
  static final long MAX_BATCH_BYTES = 10L * 1024L * 1024L;
  // Cap on buffered bytes while AMS is unreachable; the oldest lines are dropped past it.
  static final long MAX_BUFFER_BYTES = 64L * 1024L * 1024L;
  // Upper bound for the final flush in the JVM shutdown hook.
  static final long SHUTDOWN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  private static volatile OptimizingLogBatcher instance;

  private final OptimizingLogBuffer buffer =
      new OptimizingLogBuffer(MAX_BUFFER_BYTES, OptimizingLogEvent.SOURCE_DRIVER);
  private final ScheduledExecutorService flushExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "amoro-optimizing-log-thrift-flush");
            thread.setDaemon(true);
            return thread;
          });
  private final ReentrantLock sendLock = new ReentrantLock();
  // Guarded by sendLock. Used to log a delivery failure once per outage, not once per flush.
  private boolean failing = false;

  public OptimizingLogBatcher(OptimizerConfig config) {
    super(config);
  }

  public static OptimizingLogBatcher initialize(OptimizerConfig config) {
    if (instance == null) {
      synchronized (OptimizingLogBatcher.class) {
        if (instance == null) {
          instance = new OptimizingLogBatcher(config);
          instance.startFlushing();
        }
      }
    }
    return instance;
  }

  public static OptimizingLogBatcher get() {
    OptimizingLogBatcher batcher = instance;
    if (batcher == null) {
      throw new IllegalStateException("OptimizingLogBatcher has not been initialized");
    }
    return batcher;
  }

  public static boolean isInitialized() {
    return instance != null;
  }

  private void startFlushing() {
    flushExecutor.scheduleWithFixedDelay(
        this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  public void offer(OptimizingLogEvent event) {
    buffer.add(event);
    if (buffer.pendingBytes() >= MAX_BATCH_BYTES) {
      try {
        flushExecutor.execute(this::flush);
      } catch (RejectedExecutionException e) {
        // Shutting down: stopAndFlush sends what is left.
      }
    }
  }

  /**
   * Sends everything buffered, stopping at the first batch that fails.
   *
   * @return true when the buffer was fully sent
   */
  public boolean flush() {
    sendLock.lock();
    try {
      return sendAll();
    } finally {
      sendLock.unlock();
    }
  }

  /**
   * Stops the periodic flush and makes a final attempt to send what is buffered. Bounded by {@link
   * #SHUTDOWN_TIMEOUT_MS} plus at most one Thrift call, so it cannot hang the JVM shutdown.
   */
  public void stopAndFlush() {
    long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
    flushExecutor.shutdown();
    try {
      flushExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      long remaining = Math.max(0, deadline - System.currentTimeMillis());
      if (sendLock.tryLock(remaining, TimeUnit.MILLISECONDS)) {
        try {
          if (!sendAll()) {
            LOG.warn(
                "{} bytes of optimizing logs were not delivered to AMS before shutdown",
                buffer.pendingBytes());
          }
        } finally {
          sendLock.unlock();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      stop();
    }
  }

  private boolean sendAll() {
    while (true) {
      List<OptimizingLogEvent> batch = buffer.drain(MAX_BATCH_BYTES);
      if (batch.isEmpty()) {
        return true;
      }
      if (!send(batch)) {
        buffer.requeue(batch);
        return false;
      }
    }
  }

  private boolean send(List<OptimizingLogEvent> batch) {
    String token = getToken();
    if (token == null) {
      // Not registered yet, or re-registering; the token listener sets the new token.
      return false;
    }
    try {
      OptimizingClientPools.getClient(getConfig().getAmsUrl()).appendLogs(token, toThrift(batch));
      if (failing) {
        LOG.info("Resumed sending optimizing logs to AMS");
        failing = false;
      }
      return true;
    } catch (Throwable t) {
      if (!failing) {
        LOG.warn(
            "Failed to send optimizing logs to AMS; keeping them buffered and retrying every {} ms",
            FLUSH_INTERVAL_MS,
            t);
        failing = true;
      }
      return false;
    }
  }

  private static List<OptimizingLogLine> toThrift(List<OptimizingLogEvent> events) {
    List<OptimizingLogLine> lines = new ArrayList<>(events.size());
    for (OptimizingLogEvent event : events) {
      OptimizingLogLine line =
          new OptimizingLogLine(
              new OptimizingTaskId(event.getProcessId(), event.getTaskId()),
              event.getNdjson(),
              event.getSequence());
      line.setSource(event.getSource());
      lines.add(line);
    }
    return lines;
  }
}
