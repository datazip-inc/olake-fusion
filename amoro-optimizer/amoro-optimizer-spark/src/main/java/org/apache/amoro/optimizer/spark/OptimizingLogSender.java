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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Buffers optimizing log lines and sends them in batches from one background thread.
 *
 * <p>A batch leaves the buffer only once {@link #send} accepted it; otherwise it goes back to the
 * front and is sent again on the next flush or {@link #deliver}. No line is dropped, and delivery
 * is at-least-once: a batch whose confirmation is lost is sent again.
 */
abstract class OptimizingLogSender {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizingLogSender.class);

  // Stays below AMS's 16 MB Thrift frame limit, with room for Thrift's per-line overhead.
  private static final long MAX_BATCH_BYTES = 8L * 1024L * 1024L;
  private static final long RETRY_INTERVAL_MS = 200L;
  private static final long SHUTDOWN_DELIVERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  /** One log line as AMS stores it, with the run and the task it belongs to. */
  static final class LogLine implements Serializable {
    private static final long serialVersionUID = 1L;

    final long processId;
    // 0 for a process-level line, which AMS writes to driver.log.
    final int taskId;
    final String ndjson;
    final long bytes;

    LogLine(long processId, int taskId, String ndjson) {
      this.processId = processId;
      this.taskId = taskId;
      this.ndjson = ndjson;
      this.bytes = ndjson.getBytes(StandardCharsets.UTF_8).length;
    }
  }

  final String target;
  // Guarded by itself.
  private final Deque<LogLine> buffer = new ArrayDeque<>();
  private final ScheduledExecutorService sendThread;
  // Written on the send thread only. Used to log an outage once, not on every retry.
  private volatile boolean failing;

  /**
   * @param target where the lines go, used in messages
   * @param flushIntervalMs how often buffered lines are sent
   */
  OptimizingLogSender(String target, long flushIntervalMs) {
    this.target = target;
    this.sendThread =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "amoro-optimizing-log-sender");
              thread.setDaemon(true);
              return thread;
            });
    sendThread.scheduleWithFixedDelay(
        this::sendAll, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    Runtime.getRuntime().addShutdownHook(new Thread(this::close, "amoro-optimizing-log-shutdown"));
  }

  /** Sends one batch; throws if the receiver did not accept it. */
  protected abstract void send(List<LogLine> batch) throws Exception;

  void add(LogLine line) {
    synchronized (buffer) {
      buffer.addLast(line);
    }
  }

  /** True while the last send failed, so callers can avoid waiting on a known outage. */
  boolean isFailing() {
    return failing;
  }

  /**
   * Sends everything buffered and waits until it is accepted, retrying until {@code timeoutMs}
   * passes.
   *
   * @return true once every buffered line was sent
   */
  boolean deliver(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    try {
      while (true) {
        Future<Boolean> sent = sendThread.submit(this::sendAll);
        try {
          if (sent.get(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)) {
            return true;
          }
        } catch (TimeoutException e) {
          // Do not leave another send queued behind one that is stuck.
          sent.cancel(false);
          return false;
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          return false;
        }
        Thread.sleep(Math.min(RETRY_INTERVAL_MS, remaining));
      }
    } catch (ExecutionException | RejectedExecutionException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void close() {
    if (!deliver(SHUTDOWN_DELIVERY_TIMEOUT_MS)) {
      LOG.warn(
          "{} optimizing log lines were not delivered to {} before shutdown", pending(), target);
    }
    sendThread.shutdownNow();
  }

  // Runs on the send thread only.
  private boolean sendAll() {
    while (true) {
      List<LogLine> batch = takeBatch();
      if (batch.isEmpty()) {
        return true;
      }
      boolean sent = false;
      try {
        send(batch);
        sent = true;
      } catch (Exception e) {
        if (!failing) {
          LOG.warn("Failed to send optimizing logs to {}; keeping them and retrying", target, e);
        }
        failing = true;
        return false;
      } finally {
        if (!sent) {
          putBack(batch);
        }
      }
      if (failing) {
        failing = false;
        LOG.info("Resumed sending optimizing logs to {}", target);
      }
    }
  }

  private List<LogLine> takeBatch() {
    synchronized (buffer) {
      List<LogLine> batch = new ArrayList<>();
      long batchBytes = 0;
      while (!buffer.isEmpty()
          && (batch.isEmpty() || batchBytes + buffer.peekFirst().bytes <= MAX_BATCH_BYTES)) {
        LogLine line = buffer.pollFirst();
        batch.add(line);
        batchBytes += line.bytes;
      }
      return batch;
    }
  }

  private void putBack(List<LogLine> batch) {
    synchronized (buffer) {
      for (int i = batch.size() - 1; i >= 0; i--) {
        buffer.addFirst(batch.get(i));
      }
    }
  }

  private int pending() {
    synchronized (buffer) {
      return buffer.size();
    }
  }
}
