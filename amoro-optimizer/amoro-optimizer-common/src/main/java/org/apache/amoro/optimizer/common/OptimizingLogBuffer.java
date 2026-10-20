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

import org.apache.amoro.log.OptimizingLogEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Uncapped FIFO of log events waiting to be sent. Thread-safe. */
public class OptimizingLogBuffer {

  private final Deque<OptimizingLogEvent> events = new ArrayDeque<>();

  public synchronized void add(OptimizingLogEvent event) {
    if (event == null || event.isEmpty()) {
      return;
    }
    events.addLast(event);
  }

  /**
   * Removes and returns the oldest events, up to {@code maxBatchBytes} (at least one event when the
   * buffer is not empty).
   */
  public synchronized List<OptimizingLogEvent> drain(long maxBatchBytes) {
    List<OptimizingLogEvent> batch = new ArrayList<>();
    long batchBytes = 0;
    while (!events.isEmpty()) {
      OptimizingLogEvent head = events.peekFirst();
      long size = head.sizeInBytes();
      if (!batch.isEmpty() && batchBytes + size > maxBatchBytes) {
        break;
      }
      events.pollFirst();
      batch.add(head);
      batchBytes += size;
    }
    return batch;
  }

  /** Puts back a batch that failed to send, ahead of newer events, keeping its order. */
  public synchronized void requeue(List<OptimizingLogEvent> batch) {
    for (int i = batch.size() - 1; i >= 0; i--) {
      events.addFirst(batch.get(i));
    }
  }

  public synchronized long pendingBytes() {
    long total = 0;
    for (OptimizingLogEvent event : events) {
      total += event.sizeInBytes();
    }
    return total;
  }

  public synchronized boolean isEmpty() {
    return events.isEmpty();
  }
}
