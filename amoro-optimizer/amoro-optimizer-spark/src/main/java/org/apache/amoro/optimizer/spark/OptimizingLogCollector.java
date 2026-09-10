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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Driver-side in-memory store for optimizing logs. Sequence is a single global counter per {@code
 * processId}, assigned as each line arrives (driver-local logs and executor RPC logs share the same
 * counter).
 *
 * <p>FILLER(need-your-change): lines are held in memory only for Phase 1; there is no size cap,
 * eviction, or disk spill. Phase 2 is expected to drain this collector to AMS.
 */
public class OptimizingLogCollector {

  private static volatile OptimizingLogCollector instance;

  private final ConcurrentMap<Long, AtomicLong> sequenceByProcess = new ConcurrentHashMap<>();
  private final ConcurrentMap<Long, ConcurrentLinkedQueue<OptimizingLogLine>> linesByProcess =
      new ConcurrentHashMap<>();

  public static OptimizingLogCollector initialize() {
    if (instance == null) {
      synchronized (OptimizingLogCollector.class) {
        if (instance == null) {
          instance = new OptimizingLogCollector();
        }
      }
    }
    return instance;
  }

  public static boolean isInitialized() {
    return instance != null;
  }

  public static OptimizingLogCollector get() {
    OptimizingLogCollector collector = instance;
    if (collector == null) {    //why not just check for instance == null?  --ASHI
      throw new IllegalStateException("OptimizingLogCollector has not been initialized on driver");
    }
    return collector;
  }

  public void accept(OptimizingLogLine line) {
    if (line == null || line.getNdjson() == null || line.getNdjson().isEmpty()) {
      return;
    }
    AtomicLong sequence =
        sequenceByProcess.computeIfAbsent(line.getProcessId(), ignored -> new AtomicLong());
    long next = sequence.incrementAndGet();
    linesByProcess
        .computeIfAbsent(line.getProcessId(), ignored -> new ConcurrentLinkedQueue<>())
        .add(line.withSequence(next));
  }

  public List<OptimizingLogLine> snapshot(long processId) {
    ConcurrentLinkedQueue<OptimizingLogLine> lines = linesByProcess.get(processId);
    if (lines == null) {
      return new ArrayList<>();
    }
    return new ArrayList<>(lines);
  }
}
