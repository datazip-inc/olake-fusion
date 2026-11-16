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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sends a JVM's optimizing log lines to the next hop from one background thread.
 *
 * <p>Logging never waits on the network: {@link #add} only buffers. A batch leaves the buffer once
 * the receiver accepted it; otherwise it is retried with a growing backoff. Lines are lost only
 * when the buffer overflows (see {@link LogBuffer}) or the JVM dies before sending them. The lines
 * this thread logs itself carry no run, so they are never shipped.
 */
final class LogShipper {

  private static final Logger LOG = LoggerFactory.getLogger(LogShipper.class);

  /** Where a shipper sends its batches. */
  interface Transport {
    /** Sends one batch; returns once the receiver accepted it, throws otherwise. */
    void send(List<LogRecord> batch) throws Exception;

    /** True when the receiver can never take logs, such as an AMS without the API. */
    default boolean isUnsupported(Throwable error) {
      return false;
    }
  }

  // Stays well below AMS's Thrift message limit.
  static final long MAX_BATCH_BYTES = 8L * 1024L * 1024L;
  private static final long MIN_BACKOFF_MS = 200L;
  private static final long MAX_BACKOFF_MS = TimeUnit.SECONDS.toMillis(5);
  // How long shipping stays off after the receiver turned out not to support it.
  private static final long UNSUPPORTED_RETRY_MS = TimeUnit.MINUTES.toMillis(5);
  private static final long DROP_REPORT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
  private static final long SHUTDOWN_DELIVERY_MS = TimeUnit.SECONDS.toMillis(10);

  private final String senderId;
  private final String target;
  private final Transport transport;
  private final long flushIntervalMs;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition workRequested = lock.newCondition();
  private final Condition progressed = lock.newCondition();
  // Guarded by lock.
  private final LogBuffer buffer;
  private boolean wakeRequested;
  private long disabledUntil;
  private volatile boolean running;
  private Thread thread;

  // Used by the send thread only.
  private boolean failing;
  private long backoffMs = MIN_BACKOFF_MS;
  private long reportedDropped;
  private long lastDropReport;

  /**
   * @param senderId identifies this JVM's lines to AMS, unique per JVM run
   * @param target where the lines go, used in messages
   */
  LogShipper(
      String senderId,
      String target,
      Transport transport,
      long bufferCapacityBytes,
      long flushIntervalMs) {
    this.senderId = senderId;
    this.target = target;
    this.transport = transport;
    this.buffer = new LogBuffer(bufferCapacityBytes);
    this.flushIntervalMs = flushIntervalMs;
  }

  /** Starts the send thread, and a shutdown hook that sends what is left when the JVM exits. */
  synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    thread = new Thread(this::run, "amoro-optimizing-log-shipper");
    thread.setDaemon(true);
    thread.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> close(SHUTDOWN_DELIVERY_MS), "amoro-optimizing-log-exit"));
  }

  /** Buffers a line this JVM logged; never blocks on the network. */
  void add(LogRecord record) {
    lock.lock();
    try {
      if (isDisabled()) {
        return;
      }
      buffer.add(record);
      record.senderId = senderId;
      record.seq = record.bufferSeq;
      if (record.priority == LogRecord.PRIORITY_HIGH) {
        // Send a WARN or ERROR soon, in case the JVM is about to die.
        wakeRequested = true;
        workRequested.signal();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Buffers lines another JVM logged, keeping their sender and sequence number. */
  void forward(List<LogRecord> records) {
    lock.lock();
    try {
      if (isDisabled()) {
        return;
      }
      records.forEach(buffer::add);
    } finally {
      lock.unlock();
    }
  }

  /** Sequence number of the last line buffered so far. */
  long lastSeq() {
    lock.lock();
    try {
      return buffer.lastSeq();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Waits until every line up to {@code seq} was sent (or dropped), for at most {@code timeoutMs}.
   * Lines buffered later do not hold it up.
   *
   * @return true when they were, false on timeout; the lines keep being sent either way
   */
  boolean awaitDelivered(long seq, long timeoutMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    lock.lock();
    try {
      if (buffer.completedThrough() >= seq) {
        return true;
      }
      wakeRequested = true;
      workRequested.signal();
      while (buffer.completedThrough() < seq) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !running) {
          return false;
        }
        progressed.awaitNanos(remaining);
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      lock.unlock();
    }
  }

  /** Sends what is buffered for at most {@code timeoutMs}, then stops the send thread. */
  void close(long timeoutMs) {
    if (!running) {
      return;
    }
    if (!awaitDelivered(lastSeq(), timeoutMs)) {
      LOG.warn("Optimizing log lines were not all delivered to {} before shutdown", target);
    }
    running = false;
    lock.lock();
    try {
      workRequested.signal();
      progressed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private void run() {
    while (running) {
      List<LogRecord> batch;
      lock.lock();
      try {
        if (!wakeRequested) {
          workRequested.await(flushIntervalMs, TimeUnit.MILLISECONDS);
        }
        wakeRequested = false;
        batch = buffer.hasPending() ? buffer.takeBatch(MAX_BATCH_BYTES) : null;
      } catch (InterruptedException e) {
        return;
      } finally {
        lock.unlock();
      }
      if (batch != null) {
        sendBatch(batch);
      }
      reportDrops();
    }
  }

  private void sendBatch(List<LogRecord> batch) {
    Throwable error = null;
    try {
      transport.send(batch);
    } catch (Throwable t) {
      error = t;
    }
    lock.lock();
    try {
      if (error == null) {
        buffer.onSent();
        // Drain a backlog without waiting a flush interval per batch.
        wakeRequested = buffer.hasPending();
      } else if (transport.isUnsupported(error)) {
        buffer.onFailed();
        buffer.clear();
        disabledUntil = System.currentTimeMillis() + UNSUPPORTED_RETRY_MS;
      } else {
        buffer.onFailed();
      }
      progressed.signalAll();
    } finally {
      lock.unlock();
    }

    if (error == null) {
      backoffMs = MIN_BACKOFF_MS;
      if (failing) {
        failing = false;
        LOG.info("Resumed sending optimizing logs to {}", target);
      }
    } else if (transport.isUnsupported(error)) {
      LOG.warn(
          "{} does not accept optimizing logs; they are not shipped for the next {} minutes",
          target,
          TimeUnit.MILLISECONDS.toMinutes(UNSUPPORTED_RETRY_MS),
          error);
    } else {
      if (!failing) {
        failing = true;
        LOG.warn("Failed to send optimizing logs to {}; keeping them and retrying", target, error);
      }
      try {
        Thread.sleep(backoffMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        running = false;
      }
      backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
    }
  }

  private void reportDrops() {
    long now = System.currentTimeMillis();
    if (now - lastDropReport < DROP_REPORT_INTERVAL_MS) {
      return;
    }
    long dropped;
    lock.lock();
    try {
      dropped = buffer.droppedTotal();
    } finally {
      lock.unlock();
    }
    if (dropped > reportedDropped) {
      LOG.warn(
          "Dropped {} optimizing log lines bound for {} because the log buffer was full",
          dropped - reportedDropped,
          target);
      reportedDropped = dropped;
    }
    lastDropReport = now;
  }

  // Guarded by lock.
  private boolean isDisabled() {
    return disabledUntil != 0 && System.currentTimeMillis() < disabledUntil;
  }
}
