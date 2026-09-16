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

import org.apache.amoro.api.OptimizingTaskId;
import org.apache.amoro.log.OptimizingLogLine;
import org.apache.amoro.shade.thrift.org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drains sequenced optimizer log lines to AMS over existing {@code OptimizingService.appendLogs}.
 * Each line stays a discrete Thrift {@code OptimizingLogLine}; batches are split at 10 MB.
 *
 * <p>FILLER(need-your-change): the pending deque is unbounded so a line is never dropped. A single
 * line larger than {@link #MAX_BATCH_BYTES} is sent as its own batch.
 */
public class OptimizingLogBatcher extends AbstractOptimizerOperator {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizingLogBatcher.class);

  // FILLER(need-your-change): flush period agreed for Phase 2.
  static final long FLUSH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);
  // FILLER(need-your-change): max Thrift payload per appendLogs call.
  static final long MAX_BATCH_BYTES = 10L * 1024L * 1024L;

  private static volatile OptimizingLogBatcher instance;

  private final ConcurrentLinkedDeque<OptimizingLogLine> pending = new ConcurrentLinkedDeque<>();
  private final AtomicLong pendingBytes = new AtomicLong();
  private final ScheduledExecutorService flushExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "amoro-optimizing-log-thrift-flush");
            thread.setDaemon(true);
            return thread;
          });
  private final Object sendLock = new Object();

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

  private void startFlushing() {
    flushExecutor.scheduleWithFixedDelay(
        this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  public void offer(OptimizingLogLine line) {
    if (line == null || line.getNdjson() == null || line.getNdjson().isEmpty()) {
      return;
    }
    pending.addLast(line);
    long size = sizeOf(line);
    if (pendingBytes.addAndGet(size) >= MAX_BATCH_BYTES) {
      flushExecutor.execute(this::flush);
    }
  }

  public void flush() {
    synchronized (sendLock) {
      List<OptimizingLogLine> drained = drainPending();
      if (drained.isEmpty()) {
        return;
      }
      List<List<OptimizingLogLine>> chunks = splitBySize(drained);
      for (int i = 0; i < chunks.size(); i++) {
        try {
          sendChunk(chunks.get(i));
        } catch (Exception e) {
          LOG.warn("Failed to append optimizing logs to AMS; re-queueing remaining chunks", e);
          for (int j = chunks.size() - 1; j >= i; j--) {
            requeue(chunks.get(j));
          }
          return;
        }
      }
    }
  }

  public void stopAndFlush() {
    flushExecutor.shutdown();
    try {
      flushExecutor.awaitTermination(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    flush();
    stop();
  }

  private List<OptimizingLogLine> drainPending() {
    List<OptimizingLogLine> drained = new ArrayList<>();
    OptimizingLogLine line;
    while ((line = pending.pollFirst()) != null) {
      drained.add(line);
      pendingBytes.addAndGet(-sizeOf(line));
    }
    return drained;
  }

  private void requeue(List<OptimizingLogLine> chunk) {
    for (int i = chunk.size() - 1; i >= 0; i--) {
      OptimizingLogLine line = chunk.get(i);
      pending.addFirst(line);
      pendingBytes.addAndGet(sizeOf(line));
    }
  }

  private void sendChunk(List<OptimizingLogLine> chunk) throws TException {
    List<org.apache.amoro.api.OptimizingLogLine> thriftLines = new ArrayList<>(chunk.size());
    for (OptimizingLogLine line : chunk) {
      thriftLines.add(toThrift(line));
    }
    callAuthenticatedAms(
        (client, token) -> {
          client.appendLogs(token, thriftLines);
          return null;
        });
  }

  private static List<List<OptimizingLogLine>> splitBySize(List<OptimizingLogLine> lines) {
    List<List<OptimizingLogLine>> chunks = new ArrayList<>();
    List<OptimizingLogLine> current = new ArrayList<>();
    long currentBytes = 0;
    for (OptimizingLogLine line : lines) {
      long size = sizeOf(line);
      if (!current.isEmpty() && currentBytes + size > MAX_BATCH_BYTES) {
        chunks.add(current);
        current = new ArrayList<>();
        currentBytes = 0;
      }
      current.add(line);
      currentBytes += size;
    }
    if (!current.isEmpty()) {
      chunks.add(current);
    }
    return chunks;
  }

  private static long sizeOf(OptimizingLogLine line) {
    return line.getNdjson().getBytes(StandardCharsets.UTF_8).length + 1L;
  }

  private static org.apache.amoro.api.OptimizingLogLine toThrift(OptimizingLogLine line) {
    org.apache.amoro.api.OptimizingLogLine thrift =
        new org.apache.amoro.api.OptimizingLogLine(
            new OptimizingTaskId(line.getProcessId(), line.getTaskId()),
            line.getNdjson(),
            line.getSequence());
    if (line.getSource() != null) {
      thrift.setSource(line.getSource());
    }
    return thrift;
  }
}
