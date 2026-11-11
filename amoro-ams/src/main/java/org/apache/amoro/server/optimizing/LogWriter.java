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

import org.apache.amoro.api.OptimizingLogLine;
import org.apache.amoro.api.OptimizingTaskId;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes optimizing logs to the AMS disk, and is their only writer. Layout matches what {@code
 * LogController} reads: {@code LOG_DIR/<processId>/driver.log} and {@code
 * LOG_DIR/<processId>/<taskId>.log}.
 *
 * <p>Lines are written in arrival order. Task id 0 marks a process-level line (task ids start at 1,
 * see {@code OptimizingQueue}), which goes to {@code driver.log}.
 *
 * <p>HA caveat: each AMS node writes to its own {@code LOG_DIR}. With more than one AMS node, the
 * dashboard only sees the logs the serving node received, unless {@code LOG_DIR} is shared or logs
 * move to a central store.
 *
 * <p>In NFS storage mode, AMS does not delete these logs; retention is handled by olake-worker.
 *
 * <p>In S3 storage mode, {@link LogUploader} turns on chunking: each local file is the buffer of
 * the file's open chunk (see {@link LogChunks}). This class tracks the buffers, and under its lock
 * closes a buffer into a pending chunk, so a write, a close and a read snapshot never interleave.
 * Uploads never run under the lock, and appends never call S3.
 */
public class LogWriter {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  // Same time format as the optimizer's JSON log lines.
  private static final DateTimeFormatter DRIVER_LOG_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static final String DRIVER_LOG_FILE = "driver.log";
  private static final String DEFAULT_LOG_DIR = "/mnt/amoro-logs/compaction";

  private static final LogWriter INSTANCE = new LogWriter(envOrDefault("LOG_DIR", DEFAULT_LOG_DIR));

  private final String logBaseDir;
  // driver.log is the only file with more than one writer (the optimizer and AMS itself both
  // append to it); every other file has a single writer. One lock is enough for either case.
  private final Object lock = new Object();
  // Guarded by lock. Null in NFS storage mode.
  private Chunking chunking;
  // Guarded by lock. Counts closed buffers, so a reader can tell that a chunk was closed while it
  // read the S3 chunks.
  private long closeGeneration;

  private LogWriter(String logBaseDir) {
    this.logBaseDir = logBaseDir;
  }

  public static LogWriter get() {
    return INSTANCE;
  }

  public static String getLogBaseDir() {
    return INSTANCE.logBaseDir;
  }

  /**
   * Creates the log directory at startup, so the dashboard finds it before the first optimizing
   * run.
   *
   * @throws UncheckedIOException if the directory cannot be created
   */
  public void createLogDir() {
    try {
      Files.createDirectories(Paths.get(logBaseDir));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create optimizing log directory " + logBaseDir, e);
    }
  }

  /** Appends lines, grouped so each target file is opened once per call. */
  public void append(List<OptimizingLogLine> lines) {
    Map<Path, StringBuilder> contentByPath = new LinkedHashMap<>();
    for (OptimizingLogLine line : lines) {
      contentByPath
          .computeIfAbsent(resolvePath(line.getTaskId()), path -> new StringBuilder())
          .append(line.getNdjson())
          .append('\n');
    }
    contentByPath.forEach(this::write);
  }

  /**
   * Appends a line written by AMS itself (not by the optimizer) to the process driver.log.
   *
   * @throws UncheckedIOException if the entry cannot be serialized or persisted; we cannot afford
   *     to silently drop a log line, so the caller sees the failure.
   */
  public void appendDriverEntry(long processId, String level, String message, String stackTrace) {
    Map<String, String> logEntry = new LinkedHashMap<>();
    logEntry.put("level", level);
    logEntry.put("time", DRIVER_LOG_TIME_FORMAT.format(Instant.now()));
    logEntry.put("processId", String.valueOf(processId));
    logEntry.put("taskId", "");
    logEntry.put("logger", "");
    logEntry.put("message", message);
    logEntry.put("stackTrace", stackTrace == null ? "" : stackTrace);
    String ndjson;
    try {
      ndjson = OBJECT_MAPPER.writeValueAsString(logEntry);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(
          "Failed to serialize " + level + " entry for driver log of process " + processId, e);
    }
    OptimizingLogLine line = new OptimizingLogLine(new OptimizingTaskId(processId, 0), ndjson);
    append(Collections.singletonList(line));
  }

  /**
   * @throws UncheckedIOException if the lines cannot be persisted; we cannot afford to silently
   *     drop a log line, so the caller sees the failure.
   */
  private void write(Path path, StringBuilder content) {
    byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
    Runnable onBufferFull = null;
    synchronized (lock) {
      try {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to persist optimizing log lines to " + path, e);
      }
      if (chunking != null) {
        BufferState state =
            chunking.buffers.computeIfAbsent(
                path, p -> new BufferState(System.currentTimeMillis()));
        state.bytes += bytes.length;
        if (state.bytes >= chunking.thresholdBytes) {
          onBufferFull = chunking.onBufferFull;
        }
      }
    }
    // Outside the lock: only wakes the uploader, never waits for it.
    if (onBufferFull != null) {
      onBufferFull.run();
    }
  }

  /**
   * Turns on chunking (S3 storage mode). Buffers already on disk are tracked as due at once.
   *
   * @param thresholdBytes buffer size that makes a chunk due
   * @param onBufferFull called, outside the lock, when a write makes a buffer reach the threshold;
   *     must not block
   */
  void enableChunking(long thresholdBytes, Runnable onBufferFull) {
    synchronized (lock) {
      chunking = new Chunking(thresholdBytes, onBufferFull);
      for (Path buffer : listBuffers()) {
        BufferState state = new BufferState(0);
        try {
          state.bytes = Files.size(buffer);
        } catch (IOException e) {
          // Size is only used for the threshold; the age of 0 already makes the buffer due.
        }
        chunking.buffers.put(buffer, state);
      }
    }
  }

  void disableChunking() {
    synchronized (lock) {
      chunking = null;
    }
  }

  /**
   * Buffers whose chunk is due: at least the threshold in size, or holding lines older than
   * maxAgeMillis. With all set, every buffer.
   */
  List<Path> dueBuffers(long nowMillis, long maxAgeMillis, boolean all) {
    synchronized (lock) {
      List<Path> due = new ArrayList<>();
      if (chunking == null) {
        return due;
      }
      chunking.buffers.forEach(
          (path, state) -> {
            if (all
                || state.bytes >= chunking.thresholdBytes
                || nowMillis - state.firstWriteMillis >= maxAgeMillis) {
              due.add(path);
            }
          });
      return due;
    }
  }

  /**
   * Closes a buffer: renames it to its pending chunk, so new lines start a fresh buffer.
   *
   * @return false if the buffer no longer exists
   * @throws IOException if the rename fails; the buffer is then kept
   */
  boolean closeBuffer(Path buffer, Path pending) throws IOException {
    synchronized (lock) {
      try {
        Files.move(buffer, pending, StandardCopyOption.ATOMIC_MOVE);
      } catch (NoSuchFileException e) {
        if (chunking != null) {
          chunking.buffers.remove(buffer);
        }
        return false;
      }
      if (chunking != null) {
        chunking.buffers.remove(buffer);
      }
      closeGeneration++;
      return true;
    }
  }

  /** Deletes a process directory if it is empty. A concurrent write recreates it. */
  boolean deleteDirIfEmpty(Path dir) {
    synchronized (lock) {
      try {
        Files.deleteIfExists(dir);
        return true;
      } catch (IOException e) {
        // Not empty (a buffer or a pending chunk is left), or not deletable now; retried later.
        return false;
      }
    }
  }

  /**
   * Snapshot of a process's local files in S3 storage mode: the open buffers and the pending
   * chunks. Pending files are immutable, so only their paths are taken; a pending file deleted
   * later was uploaded first, so the reader then finds it in S3.
   *
   * @throws UncheckedIOException if the directory cannot be listed or a buffer cannot be read
   */
  LocalSnapshot snapshot(long processId) {
    Path dir = processDir(processId);
    synchronized (lock) {
      LocalSnapshot snapshot = new LocalSnapshot(closeGeneration);
      if (!Files.isDirectory(dir)) {
        return snapshot;
      }
      try (DirectoryStream<Path> stream =
          Files.newDirectoryStream(dir, "*" + LogChunks.BUFFER_SUFFIX)) {
        for (Path file : stream) {
          String bufferBase = LogChunks.bufferBase(file.getFileName().toString());
          if (bufferBase != null) {
            snapshot.buffers.put(bufferBase, Files.readAllBytes(file));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read local optimizing logs in " + dir, e);
      }
      snapshot.pending.putAll(LogChunks.listPending(dir));
      return snapshot;
    }
  }

  long closeGeneration() {
    synchronized (lock) {
      return closeGeneration;
    }
  }

  Path baseDir() {
    return Paths.get(logBaseDir);
  }

  Path processDir(long processId) {
    return Paths.get(logBaseDir, String.valueOf(processId));
  }

  private List<Path> listBuffers() {
    List<Path> buffers = new ArrayList<>();
    Path base = baseDir();
    if (!Files.isDirectory(base)) {
      return buffers;
    }
    try (DirectoryStream<Path> processDirs =
        Files.newDirectoryStream(
            base, path -> Files.isDirectory(path) && LogChunks.isProcessDir(path))) {
      for (Path dir : processDirs) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
          for (Path file : files) {
            if (LogChunks.bufferBase(file.getFileName().toString()) != null) {
              buffers.add(file);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list optimizing log buffers in " + base, e);
    }
    return buffers;
  }

  private Path resolvePath(OptimizingTaskId taskId) {
    Path processDir = Paths.get(logBaseDir, String.valueOf(taskId.getProcessId()));
    return processDir.resolve(
        taskId.getTaskId() == 0 ? DRIVER_LOG_FILE : taskId.getTaskId() + ".log");
  }

  /** Local files of one process, taken under the lock. */
  static final class LocalSnapshot {
    final long closeGeneration;
    // File base -> content of the open buffer.
    final Map<String, byte[]> buffers = new HashMap<>();
    // File base -> counter -> pending chunk file.
    final Map<String, TreeMap<Long, Path>> pending = new HashMap<>();

    LocalSnapshot(long closeGeneration) {
      this.closeGeneration = closeGeneration;
    }
  }

  private static final class Chunking {
    final long thresholdBytes;
    final Runnable onBufferFull;
    final Map<Path, BufferState> buffers = new HashMap<>();

    Chunking(long thresholdBytes, Runnable onBufferFull) {
      this.thresholdBytes = thresholdBytes;
      this.onBufferFull = onBufferFull;
    }
  }

  private static final class BufferState {
    final long firstWriteMillis;
    long bytes;

    BufferState(long firstWriteMillis) {
      this.firstWriteMillis = firstWriteMillis;
    }
  }

  private static String envOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isEmpty() ? defaultValue : value;
  }
}
