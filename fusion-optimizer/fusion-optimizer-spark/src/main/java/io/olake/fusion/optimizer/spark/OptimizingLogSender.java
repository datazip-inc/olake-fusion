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

package io.olake.fusion.optimizer.spark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Buffers optimizing log lines and sends them in batches from one background thread. Callers never
 * wait on a send: {@link #add} only buffers, and {@link #flushAsync} only asks the send thread to
 * send now.
 *
 * <p>A batch leaves the buffer only once {@link #send} accepted it; otherwise it goes back to the
 * front and is sent again on the next flush. Delivery is at-least-once: a batch whose confirmation
 * is lost is sent again.
 *
 * <p>The buffer holds at most {@code maxBufferedBytes}, so an unreachable receiver cannot run the
 * JVM out of memory. A line that does not fit is dropped and counted against its task (see {@link
 * #takeDropped}).
 */
abstract class OptimizingLogSender {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizingLogSender.class);

  // Stays below AMS's 16 MB Thrift frame limit, with room for Thrift's per-line overhead.
  private static final long MAX_BATCH_BYTES = 8L * 1024L * 1024L;
  private static final long RETRY_INTERVAL_MS = 200L;
  private static final long SHUTDOWN_DELIVERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  /**
   * One log line as AMS stores it, with the run and the task it belongs to; or the end marker of a
   * task, which follows all the lines of its task in the buffer.
   */
  static final class LogLine implements Serializable {
    private static final long serialVersionUID = 1L;

    final long processId;
    // 0 for a process-level line, which AMS writes to driver.log.
    final int taskId;
    // Null for a task end marker.
    final String ndjson;
    final long bytes;
    // Task end marker only: lines of the task that the sending JVM dropped.
    final int droppedLines;

    LogLine(long processId, int taskId, String ndjson) {
      this(processId, taskId, ndjson, ndjson.getBytes(StandardCharsets.UTF_8).length, 0);
    }

    private LogLine(long processId, int taskId, String ndjson, long bytes, int droppedLines) {
      this.processId = processId;
      this.taskId = taskId;
      this.ndjson = ndjson;
      this.bytes = bytes;
      this.droppedLines = droppedLines;
    }

    static LogLine taskEnd(long processId, int taskId, int droppedLines) {
      return new LogLine(processId, taskId, null, 0, droppedLines);
    }

    /** A line that is let in even when the buffer is full, as it is small and must not be lost. */
    static LogLine unbounded(long processId, int taskId, String text) {
      return new LogLine(processId, taskId, text, 0, 0);
    }

    boolean isTaskEnd() {
      return ndjson == null;
    }

    TaskKey key() {
      return new TaskKey(processId, taskId);
    }
  }

  static final class TaskKey {
    final long processId;
    final int taskId;

    TaskKey(long processId, int taskId) {
      this.processId = processId;
      this.taskId = taskId;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof TaskKey)) {
        return false;
      }
      TaskKey other = (TaskKey) o;
      return processId == other.processId && taskId == other.taskId;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(processId) * 31 + taskId;
    }

    @Override
    public String toString() {
      return processId + "/" + taskId;
    }
  }

  final String target;
  private final long maxBufferedBytes;
  // Guards the fields below, and is the monitor that waiters on sentLines wait on.
  protected final Object lock = new Object();
  private final Deque<LogLine> buffer = new ArrayDeque<>();
  // Bytes of lines buffered or in flight.
  private long bufferedBytes;
  // Lines ever buffered, and lines the receiver accepted. Lines leave the buffer in order, so line
  // number n has been delivered once sentLines >= n.
  protected long addedLines;
  protected long sentLines;
  private final Map<TaskKey, Integer> droppedByTask = new HashMap<>();
  // Set once the receiver turned out not to support log shipping: lines are then discarded.
  protected boolean disabled;

  private final ScheduledExecutorService sendThread;
  // Set while a requested flush is queued, so a burst of ERROR lines queues only one.
  private final AtomicBoolean flushQueued = new AtomicBoolean();
  // Written on the send thread only. Used to log an outage once, not on every retry.
  private volatile boolean failing;

  /**
   * @param target where the lines go, used in messages
   * @param flushIntervalMs how often buffered lines are sent
   * @param maxBufferedBytes how many bytes of lines the buffer holds at most
   */
  OptimizingLogSender(String target, long flushIntervalMs, long maxBufferedBytes) {
    this.target = target;
    this.maxBufferedBytes = maxBufferedBytes;
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

  /**
   * Called under {@link #lock} for a task end marker. By default the marker is buffered and sent on
   * to the next hop, behind the task's lines.
   */
  protected void onTaskEnd(LogLine marker) {
    buffer.addLast(marker);
  }

  /** Buffers a line; a line that does not fit is dropped and counted against its task. */
  void add(LogLine line) {
    synchronized (lock) {
      if (line.isTaskEnd()) {
        onTaskEnd(line);
      } else if (disabled) {
        return;
      } else if (bufferedBytes + line.bytes > maxBufferedBytes) {
        droppedByTask.merge(line.key(), 1, Integer::sum);
      } else {
        append(line);
      }
    }
  }

  /**
   * Buffers all the lines or none of them, so a refused batch can be sent again as a whole.
   *
   * @return false if the lines do not fit
   */
  boolean offerAll(List<LogLine> lines) {
    synchronized (lock) {
      long bytes = 0;
      for (LogLine line : lines) {
        bytes += line.bytes;
      }
      if (!disabled && bytes > 0 && bufferedBytes + bytes > maxBufferedBytes) {
        return false;
      }
      for (LogLine line : lines) {
        if (line.isTaskEnd()) {
          onTaskEnd(line);
        } else if (!disabled) {
          append(line);
        }
      }
      return true;
    }
  }

  /** Returns and resets the number of lines of a task this JVM dropped. */
  int takeDropped(long processId, int taskId) {
    synchronized (lock) {
      Integer dropped = droppedByTask.remove(new TaskKey(processId, taskId));
      return dropped == null ? 0 : dropped;
    }
  }

  /** Asks the send thread to send what is buffered now, without waiting for it. */
  void flushAsync() {
    if (flushQueued.compareAndSet(false, true)) {
      try {
        sendThread.execute(
            () -> {
              flushQueued.set(false);
              sendAll();
            });
      } catch (RejectedExecutionException e) {
        flushQueued.set(false);
      }
    }
  }

  /**
   * Stops shipping for good: discards what is buffered and every later line. For a receiver that
   * does not support log shipping.
   */
  void disable() {
    synchronized (lock) {
      disabled = true;
      buffer.removeIf(line -> !line.isTaskEnd());
      bufferedBytes = 0;
      sentLines = addedLines;
      lock.notifyAll();
    }
  }

  /**
   * Sends everything buffered and waits until it is accepted, retrying until {@code timeoutMs}
   * passes. Used at shutdown only.
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

  // Under lock.
  private void append(LogLine line) {
    buffer.addLast(line);
    bufferedBytes += line.bytes;
    addedLines++;
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
        if (sent) {
          confirm(batch);
        } else {
          putBack(batch);
        }
      }
      if (failing) {
        failing = false;
        LOG.info("Resumed sending optimizing logs to {}", target);
      }
    }
  }

  // The batch's bytes stay counted in bufferedBytes until it is confirmed or put back.
  private List<LogLine> takeBatch() {
    synchronized (lock) {
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

  private void confirm(List<LogLine> batch) {
    synchronized (lock) {
      if (disabled) {
        return;
      }
      for (LogLine line : batch) {
        bufferedBytes -= line.bytes;
        if (!line.isTaskEnd()) {
          sentLines++;
        }
      }
      lock.notifyAll();
    }
  }

  private void putBack(List<LogLine> batch) {
    synchronized (lock) {
      for (int i = batch.size() - 1; i >= 0; i--) {
        LogLine line = batch.get(i);
        if (!disabled || line.isTaskEnd()) {
          buffer.addFirst(line);
        }
      }
    }
  }

  private int pending() {
    synchronized (lock) {
      return buffer.size();
    }
  }
}
