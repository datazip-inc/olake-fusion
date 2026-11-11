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

package org.apache.amoro.server.optimizing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploads optimizing logs to S3 as numbered chunks, on one background thread, so appends never wait
 * on S3. Each tick it closes due buffers into pending chunks (under the {@link LogWriter} lock),
 * then uploads the pending chunks in counter order without the lock and deletes each after its
 * upload succeeds. A failed upload stays on disk and is retried on the next tick; later chunks of
 * the same file wait, so the order holds.
 *
 * <p>A chunk is due when its buffer reaches 1 MiB (the append wakes this thread at once) or holds
 * lines older than 30 s. On shutdown, every buffer is closed and uploaded twice: by {@link
 * #flushAll()} right after the Thrift server stops, and by {@link #dispose()} at the end.
 */
public class LogUploader {

  private static final Logger LOG = LoggerFactory.getLogger(LogUploader.class);

  // Same as olake-worker's largest (steady-state) chunk size.
  static final long CHUNK_THRESHOLD_BYTES = 1 << 20;
  static final long FLUSH_INTERVAL_MS = 30_000;
  private static final long TICK_MS = 5_000;
  private static final long FINAL_FLUSH_TIMEOUT_MS = 30_000;

  private final S3LogStore store;
  private final LogWriter writer;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean wakeQueued = new AtomicBoolean();
  // Uploader thread only. "<processId>/<fileBase>" -> counter of the next chunk.
  private final Map<String, Long> nextCounters = new HashMap<>();
  // Uploader thread only. Used to log an outage once, not on every retry.
  private boolean failing;

  public LogUploader(S3LogStore store, LogWriter writer) {
    this.store = store;
    this.writer = writer;
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "optimizing-log-uploader");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Turns on chunking and starts the ticks. The first tick uploads what a restart left behind. */
  public void start() {
    writer.enableChunking(CHUNK_THRESHOLD_BYTES, this::wake);
    executor.scheduleWithFixedDelay(() -> runSafely(false), 0, TICK_MS, TimeUnit.MILLISECONDS);
    LOG.info("Optimizing log uploader started, target {}", store.describe());
  }

  /**
   * Closes and uploads every buffer now, waiting up to 30 s, and keeps running. Called on shutdown
   * right after the Thrift server stops, so the optimizer's lines reach S3 before the slower
   * services stop and the pod's grace period may end.
   */
  public void flushAll() {
    try {
      executor.submit(() -> runSafely(true)).get(FINAL_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      LOG.warn("Upload of optimizing logs did not finish in time; it continues in the background");
    } catch (RejectedExecutionException | ExecutionException e) {
      // Already disposed, or runSafely failed (it logs its own errors).
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Closes and uploads every buffer once more, waiting up to 30 s, then stops. */
  public void dispose() {
    try {
      executor.submit(() -> runSafely(true));
      // Periodic ticks are cancelled on shutdown; the final flush submitted above still runs.
      executor.shutdown();
      if (!executor.awaitTermination(FINAL_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        LOG.warn("Final upload of optimizing logs did not finish in time; logs stay on disk");
      }
    } catch (RejectedExecutionException e) {
      // Already disposed.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      executor.shutdownNow();
      writer.disableChunking();
    }
  }

  /** Called by an append whose buffer reached the threshold. Never blocks. */
  private void wake() {
    if (!wakeQueued.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            wakeQueued.set(false);
            runSafely(false);
          });
    } catch (RejectedExecutionException e) {
      wakeQueued.set(false);
    }
  }

  private void runSafely(boolean closeAll) {
    try {
      boolean ok = closeDueBuffers(closeAll) & uploadPending();
      if (ok && failing) {
        failing = false;
        LOG.info("Uploading optimizing logs to {} works again", store.describe());
      }
    } catch (Throwable t) {
      reportFailure("Unexpected error while uploading optimizing logs", t);
    }
  }

  /** Renames each due buffer to its next pending chunk. Returns false if one failed. */
  private boolean closeDueBuffers(boolean closeAll) {
    boolean ok = true;
    // processId -> chunk names in S3, listed at most once per tick.
    Map<Long, List<String>> remoteChunks = new HashMap<>();
    for (Path buffer : writer.dueBuffers(System.currentTimeMillis(), FLUSH_INTERVAL_MS, closeAll)) {
      Path dir = buffer.getParent();
      String fileBase = LogChunks.bufferBase(buffer.getFileName().toString());
      long processId;
      try {
        processId = Long.parseLong(dir.getFileName().toString());
      } catch (NumberFormatException e) {
        continue;
      }
      String counterKey = processId + "/" + fileBase;
      try {
        Long counter = nextCounters.get(counterKey);
        if (counter == null) {
          List<String> remote =
              remoteChunks.computeIfAbsent(
                  processId, id -> store.listNames(store.processPrefix(id)));
          counter = resolveNextCounter(dir, fileBase, remote);
        }
        if (writer.closeBuffer(buffer, dir.resolve(LogChunks.pendingName(fileBase, counter)))) {
          nextCounters.put(counterKey, counter + 1);
        }
      } catch (IOException | RuntimeException e) {
        ok = false;
        reportFailure("Failed to close optimizing log chunk " + buffer, e);
      }
    }
    return ok;
  }

  /**
   * Next counter after a restart or after the process directory was deleted: one more than the
   * highest chunk in S3 or on local disk.
   */
  private long resolveNextCounter(Path dir, String fileBase, List<String> remoteChunks)
      throws IOException {
    long highest = 0;
    for (String name : remoteChunks) {
      highest = Math.max(highest, counterOf(name, fileBase));
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
      for (Path file : files) {
        highest = Math.max(highest, counterOf(file.getFileName().toString(), fileBase));
      }
    }
    return highest + 1;
  }

  private static long counterOf(String name, String fileBase) {
    LogChunks.Chunk chunk = LogChunks.parseChunk(name);
    return chunk != null && chunk.fileBase.equals(fileBase) ? chunk.counter : 0;
  }

  /** Uploads pending chunks in counter order and deletes each after success. */
  private boolean uploadPending() {
    boolean ok = true;
    for (Path dir : processDirs()) {
      long processId = Long.parseLong(dir.getFileName().toString());
      Map<String, TreeMap<Long, Path>> pending = LogChunks.listPending(dir);
      for (Map.Entry<String, TreeMap<Long, Path>> file : pending.entrySet()) {
        for (Map.Entry<Long, Path> chunk : file.getValue().entrySet()) {
          String key = store.chunkKey(processId, file.getKey(), chunk.getKey());
          try {
            store.put(key, chunk.getValue());
            Files.delete(chunk.getValue());
          } catch (IOException | RuntimeException e) {
            ok = false;
            reportFailure("Failed to upload optimizing log chunk " + chunk.getValue(), e);
            // Later chunks of this file wait, so chunks reach S3 in order.
            break;
          }
        }
      }
      if (writer.deleteDirIfEmpty(dir)) {
        String prefix = processId + "/";
        nextCounters.keySet().removeIf(key -> key.startsWith(prefix));
      }
    }
    return ok;
  }

  private List<Path> processDirs() {
    List<Path> dirs = new ArrayList<>();
    Path base = writer.baseDir();
    if (!Files.isDirectory(base)) {
      return dirs;
    }
    try (DirectoryStream<Path> stream =
        Files.newDirectoryStream(
            base, path -> Files.isDirectory(path) && LogChunks.isProcessDir(path))) {
      stream.forEach(dirs::add);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list optimizing log directories in " + base, e);
    }
    return dirs;
  }

  private void reportFailure(String message, Throwable t) {
    if (!failing) {
      failing = true;
      LOG.warn("{}; keeping logs on disk and retrying every {} ms", message, TICK_MS, t);
    } else {
      LOG.debug(message, t);
    }
  }
}
