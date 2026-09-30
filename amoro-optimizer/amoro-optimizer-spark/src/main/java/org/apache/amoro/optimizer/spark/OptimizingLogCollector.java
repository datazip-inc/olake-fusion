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
import org.apache.amoro.optimizer.common.OptimizingLogBatcher;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Driver-side sequencing point for optimizing logs. Driver-local events and executor events (via
 * Spark RPC) share one counter per {@code processId}, assigned as each event arrives. Sequenced
 * events are offered to {@link OptimizingLogBatcher} for Thrift upload to AMS.
 *
 * <p>AMS does not use the sequence yet; it is carried so a future log destination can order lines
 * that arrive out of order.
 */
public class OptimizingLogCollector {

  // A process with no log line for this long is forgotten; its counter restarts if it logs again.
  static final long IDLE_PROCESS_EVICT_MS = TimeUnit.HOURS.toMillis(1);
  private static final int EVICT_CHECK_EVERY = 1024;

  private static volatile OptimizingLogCollector instance;

  private final ConcurrentMap<Long, ProcessSequence> sequenceByProcess = new ConcurrentHashMap<>();
  private final AtomicLong acceptedCount = new AtomicLong();
  private final OptimizingLogBatcher batcher;

  private OptimizingLogCollector(OptimizingLogBatcher batcher) {
    this.batcher = batcher;
  }

  public static OptimizingLogCollector initialize(OptimizingLogBatcher batcher) {
    if (instance == null) {
      synchronized (OptimizingLogCollector.class) {
        if (instance == null) {
          instance = new OptimizingLogCollector(batcher);
        }
      }
    }
    return instance;
  }

  /** Returns the driver collector, or null when this JVM is not a driver with log shipping. */
  public static OptimizingLogCollector getIfInitialized() {
    return instance;
  }

  public void accept(OptimizingLogEvent event) {
    if (event == null || event.isEmpty()) {
      return;
    }
    long now = System.currentTimeMillis();
    ProcessSequence sequence =
        sequenceByProcess.computeIfAbsent(event.getProcessId(), ignored -> new ProcessSequence());
    sequence.lastUsedMs = now;
    batcher.offer(event.withSequence(sequence.counter.incrementAndGet()));
    if (acceptedCount.incrementAndGet() % EVICT_CHECK_EVERY == 0) {
      sequenceByProcess.values().removeIf(s -> now - s.lastUsedMs > IDLE_PROCESS_EVICT_MS);
    }
  }

  private static class ProcessSequence {
    private final AtomicLong counter = new AtomicLong();
    private volatile long lastUsedMs;
  }
}
