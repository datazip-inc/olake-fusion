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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log lines waiting to be sent, in the order they were added, within a size limit.
 *
 * <p>When the limit is reached the oldest line of the lowest priority is dropped: DEBUG before INFO
 * before WARN and ERROR. A new line is dropped instead when every buffered line has a higher
 * priority. Dropped lines are counted per run and task, and the next batch starts with a {@link
 * LogRecord#gapMarker} for each, so a task log shows where lines are missing.
 *
 * <p>Not thread-safe: {@link LogShipper} guards it.
 */
final class LogBuffer {

  private final long capacityBytes;
  private final ArrayDeque<LogRecord>[] queues;
  private final ArrayDeque<LogRecord> gapMarkers = new ArrayDeque<>();
  // Keyed by "processId/taskId", in the order drops happened.
  private final Map<String, long[]> droppedByTask = new LinkedHashMap<>();
  private long queuedBytes;
  private long lastSeq;
  private long droppedTotal;
  // The batch taken by the sender and not yet confirmed or put back.
  private List<LogRecord> inFlight = Collections.emptyList();
  private long inFlightFirstSeq;

  @SuppressWarnings("unchecked")
  LogBuffer(long capacityBytes) {
    this.capacityBytes = capacityBytes;
    this.queues = new ArrayDeque[LogRecord.PRIORITY_HIGH + 1];
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new ArrayDeque<>();
    }
  }

  /** Adds a line, dropping lower-priority lines if needed; never blocks. */
  void add(LogRecord record) {
    record.bufferSeq = ++lastSeq;
    if (!makeRoom(record)) {
      countDropped(record);
      return;
    }
    queues[record.priority].addLast(record);
    queuedBytes += record.bytes;
  }

  /**
   * Takes the oldest lines, gap markers first, up to {@code maxBytes} (at least one line). The
   * batch stays in flight until {@link #onSent} or {@link #onFailed}.
   */
  List<LogRecord> takeBatch(long maxBytes) {
    if (!inFlight.isEmpty()) {
      throw new IllegalStateException("A batch is already in flight");
    }
    droppedByTask.forEach(
        (task, count) -> {
          String[] ids = task.split("/");
          gapMarkers.addLast(
              LogRecord.gapMarker(Long.parseLong(ids[0]), Integer.parseInt(ids[1]), count[0]));
        });
    droppedByTask.clear();

    List<LogRecord> batch = new ArrayList<>();
    long batchBytes = 0;
    while (!gapMarkers.isEmpty()) {
      LogRecord marker = gapMarkers.pollFirst();
      batch.add(marker);
      batchBytes += marker.bytes;
    }
    inFlightFirstSeq = 0;
    while (true) {
      ArrayDeque<LogRecord> oldest = null;
      for (ArrayDeque<LogRecord> queue : queues) {
        if (!queue.isEmpty()
            && (oldest == null || queue.peekFirst().bufferSeq < oldest.peekFirst().bufferSeq)) {
          oldest = queue;
        }
      }
      if (oldest == null
          || (!batch.isEmpty() && batchBytes + oldest.peekFirst().bytes > maxBytes)) {
        break;
      }
      LogRecord record = oldest.pollFirst();
      queuedBytes -= record.bytes;
      if (inFlightFirstSeq == 0) {
        inFlightFirstSeq = record.bufferSeq;
      }
      batch.add(record);
      batchBytes += record.bytes;
    }
    inFlight = batch;
    return batch;
  }

  void onSent() {
    inFlight = Collections.emptyList();
  }

  /** Puts the in-flight batch back at the front, in its original order. */
  void onFailed() {
    for (int i = inFlight.size() - 1; i >= 0; i--) {
      LogRecord record = inFlight.get(i);
      if (record.isGapMarker()) {
        gapMarkers.addFirst(record);
      } else {
        queues[record.priority].addFirst(record);
        queuedBytes += record.bytes;
      }
    }
    inFlight = Collections.emptyList();
  }

  /** Drops everything buffered, for when the receiver cannot take logs at all. */
  void clear() {
    for (ArrayDeque<LogRecord> queue : queues) {
      queue.clear();
    }
    gapMarkers.clear();
    droppedByTask.clear();
    queuedBytes = 0;
  }

  boolean hasPending() {
    if (!gapMarkers.isEmpty() || !droppedByTask.isEmpty()) {
      return true;
    }
    for (ArrayDeque<LogRecord> queue : queues) {
      if (!queue.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** Sequence number of the last line added. */
  long lastSeq() {
    return lastSeq;
  }

  /** Every line up to this sequence number was either sent or dropped. */
  long completedThrough() {
    long oldestPending = inFlightFirstSeq != 0 && !inFlight.isEmpty() ? inFlightFirstSeq : 0;
    for (ArrayDeque<LogRecord> queue : queues) {
      if (!queue.isEmpty() && (oldestPending == 0 || queue.peekFirst().bufferSeq < oldestPending)) {
        oldestPending = queue.peekFirst().bufferSeq;
      }
    }
    return oldestPending == 0 ? lastSeq : oldestPending - 1;
  }

  long droppedTotal() {
    return droppedTotal;
  }

  private boolean makeRoom(LogRecord record) {
    while (queuedBytes > 0 && queuedBytes + record.bytes > capacityBytes) {
      int lowest = -1;
      for (int i = 0; i < queues.length; i++) {
        if (!queues[i].isEmpty()) {
          lowest = i;
          break;
        }
      }
      if (lowest > record.priority) {
        return false;
      }
      LogRecord victim = queues[lowest].pollFirst();
      queuedBytes -= victim.bytes;
      countDropped(victim);
    }
    return true;
  }

  private void countDropped(LogRecord record) {
    droppedTotal++;
    droppedByTask.computeIfAbsent(record.processId + "/" + record.taskId, k -> new long[1])[0]++;
  }
}
