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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Size-capped FIFO of log events waiting to be sent. Thread-safe.
 *
 * <p>When the cap is exceeded the oldest events are dropped and counted per process. The next
 * {@link #drain} starts with one WARN notice per affected process, so the gap shows up in that
 * process's {@code driver.log} once delivery resumes.
 */
public class OptimizingLogBuffer {

  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final long maxBytes;
  private final String origin;
  private final Deque<OptimizingLogEvent> events = new ArrayDeque<>();
  private final Map<Long, Long> droppedByProcess = new LinkedHashMap<>();
  private long bytes;

  /**
   * @param maxBytes cap on buffered bytes; oldest events are dropped past it
   * @param origin where the buffer lives ("driver" or "executor"), used in the drop notice
   */
  public OptimizingLogBuffer(long maxBytes, String origin) {
    this.maxBytes = maxBytes;
    this.origin = origin;
  }

  public synchronized void add(OptimizingLogEvent event) {
    if (event == null || event.isEmpty()) {
      return;
    }
    events.addLast(event);
    bytes += event.sizeInBytes();
    evictOverflow();
  }

  /**
   * Removes and returns the oldest events, up to {@code maxBatchBytes} (at least one event when the
   * buffer is not empty). Pending drop notices come first.
   */
  public synchronized List<OptimizingLogEvent> drain(long maxBatchBytes) {
    List<OptimizingLogEvent> batch = new ArrayList<>();
    long batchBytes = 0;
    for (Map.Entry<Long, Long> dropped : droppedByProcess.entrySet()) {
      DropNotice notice = new DropNotice(dropped.getKey(), dropped.getValue(), origin);
      batch.add(notice);
      batchBytes += notice.sizeInBytes();
    }
    droppedByProcess.clear();
    while (!events.isEmpty()) {
      OptimizingLogEvent head = events.peekFirst();
      long size = head.sizeInBytes();
      if (!batch.isEmpty() && batchBytes + size > maxBatchBytes) {
        break;
      }
      events.pollFirst();
      bytes -= size;
      batch.add(head);
      batchBytes += size;
    }
    return batch;
  }

  /** Puts back a batch that failed to send, ahead of newer events, keeping its order. */
  public synchronized void requeue(List<OptimizingLogEvent> batch) {
    for (int i = batch.size() - 1; i >= 0; i--) {
      OptimizingLogEvent event = batch.get(i);
      if (event instanceof DropNotice) {
        // Keep the count, not the rendered line, so a later drain reports the full total.
        droppedByProcess.merge(event.getProcessId(), ((DropNotice) event).count, Long::sum);
      } else {
        events.addFirst(event);
        bytes += event.sizeInBytes();
      }
    }
    evictOverflow();
  }

  public synchronized long pendingBytes() {
    return bytes;
  }

  public synchronized boolean isEmpty() {
    return events.isEmpty() && droppedByProcess.isEmpty();
  }

  private void evictOverflow() {
    while (bytes > maxBytes && events.size() > 1) {
      OptimizingLogEvent oldest = events.pollFirst();
      bytes -= oldest.sizeInBytes();
      droppedByProcess.merge(oldest.getProcessId(), 1L, Long::sum);
    }
  }

  /** Driver-log WARN line that reports how many lines of one process were dropped. */
  static class DropNotice extends OptimizingLogEvent {
    private static final long serialVersionUID = 1L;

    private final long count;

    DropNotice(long processId, long count, String origin) {
      super(processId, 0, render(processId, count, origin), SOURCE_DRIVER);
      this.count = count;
    }

    private static String render(long processId, long count, String origin) {
      return "{\"level\":\"WARN\",\"time\":\""
          + TIME_FORMAT.format(Instant.now())
          + "\",\"processId\":\""
          + processId
          + "\",\"taskId\":\"\",\"logger\":\"OptimizingLogBuffer\",\"message\":\""
          + count
          + " log lines of this process were dropped on the optimizer "
          + origin
          + " because its log buffer was full\",\"stackTrace\":\"\"}";
    }
  }
}
