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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads optimizing logs in S3 storage mode. A file is its chunks in counter order (uploaded chunks
 * in S3 and pending chunks on local disk), followed by the open local buffer.
 *
 * <p>The local part is taken as one snapshot under the {@link LogWriter} lock. If a buffer is
 * closed while the S3 chunks are read, the read is repeated, so no line is lost or counted twice.
 */
public class S3LogReader {

  private static final int MAX_ATTEMPTS = 5;
  private static final Comparator<String> FILE_ORDER =
      Comparator.comparing((String base) -> !LogChunks.DRIVER_FILE_BASE.equals(base))
          .thenComparing(
              base -> LogChunks.DRIVER_FILE_BASE.equals(base) ? 0L : Long.parseLong(base));

  private final S3LogStore store;
  private final LogWriter writer;

  public S3LogReader(S3LogStore store, LogWriter writer) {
    this.store = store;
    this.writer = writer;
  }

  /**
   * Reads every log file of a process, driver first, then tasks by id.
   *
   * @return file base -> content; empty if the process has no logs
   * @throws UncheckedIOException if local files or S3 cannot be read
   */
  public Map<String, byte[]> readAll(long processId) {
    return readFiles(processId, null);
  }

  /**
   * Reads one log file of a process.
   *
   * @return the content, or null if the file has no logs
   * @throws UncheckedIOException if local files or S3 cannot be read
   */
  public byte[] read(long processId, String fileBase) {
    return readFiles(processId, fileBase).get(fileBase);
  }

  private Map<String, byte[]> readFiles(long processId, String onlyFileBase) {
    Map<String, byte[]> files = null;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      LogWriter.LocalSnapshot local = writer.snapshot(processId);
      files = merge(processId, onlyFileBase, local);
      if (writer.closeGeneration() == local.closeGeneration) {
        break;
      }
    }
    return files;
  }

  private Map<String, byte[]> merge(
      long processId, String onlyFileBase, LogWriter.LocalSnapshot local) {
    Map<String, TreeSet<Long>> remote = new HashMap<>();
    for (String name : store.listNames(store.processPrefix(processId))) {
      LogChunks.Chunk chunk = LogChunks.parseChunk(name);
      if (chunk != null && !chunk.pending) {
        remote.computeIfAbsent(chunk.fileBase, base -> new TreeSet<>()).add(chunk.counter);
      }
    }

    Set<String> fileBases = new HashSet<>(remote.keySet());
    fileBases.addAll(local.pending.keySet());
    fileBases.addAll(local.buffers.keySet());
    if (onlyFileBase != null) {
      fileBases.retainAll(Set.of(onlyFileBase));
    }
    List<String> ordered = new ArrayList<>(fileBases);
    ordered.sort(FILE_ORDER);

    Map<String, byte[]> files = new LinkedHashMap<>();
    for (String fileBase : ordered) {
      ByteArrayOutputStream content = new ByteArrayOutputStream();
      TreeMap<Long, Path> pending = local.pending.getOrDefault(fileBase, new TreeMap<>());
      TreeSet<Long> counters = new TreeSet<>(remote.getOrDefault(fileBase, new TreeSet<>()));
      counters.addAll(pending.keySet());
      for (long counter : counters) {
        content.writeBytes(readChunk(processId, fileBase, counter, pending.get(counter)));
      }
      byte[] buffer = local.buffers.get(fileBase);
      if (buffer != null) {
        content.writeBytes(buffer);
      }
      files.put(fileBase, content.toByteArray());
    }
    return files;
  }

  /** Reads a chunk from its pending file if still there, else from S3. */
  private byte[] readChunk(long processId, String fileBase, long counter, Path pendingFile) {
    if (pendingFile != null) {
      try {
        return Files.readAllBytes(pendingFile);
      } catch (NoSuchFileException e) {
        // Deleted after its upload succeeded, so S3 has it.
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read pending log chunk " + pendingFile, e);
      }
    }
    byte[] content = store.get(store.chunkKey(processId, fileBase, counter));
    return content == null ? new byte[0] : content;
  }
}
