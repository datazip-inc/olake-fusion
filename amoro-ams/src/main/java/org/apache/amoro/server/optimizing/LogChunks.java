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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File names used when optimizing logs are stored in S3. A log file ({@code driver} or a task id)
 * is split into numbered chunks; joined in counter order they are the full file.
 *
 * <ul>
 *   <li>{@code <file>.log}: local buffer of the open chunk; receives new lines.
 *   <li>{@code <file>-<counter>.pending}: local closed chunk, waiting for upload.
 *   <li>{@code <file>-<counter>.log}: uploaded chunk in S3.
 * </ul>
 */
public final class LogChunks {

  public static final String DRIVER_FILE_BASE = "driver";
  static final String BUFFER_SUFFIX = ".log";
  static final String PENDING_SUFFIX = ".pending";

  private static final Pattern FILE_BASE = Pattern.compile("driver|[0-9]+");
  private static final Pattern PROCESS_ID = Pattern.compile("[0-9]+");
  private static final Pattern CHUNK =
      Pattern.compile("(driver|[0-9]+)-([0-9]+)(" + Pattern.quote(BUFFER_SUFFIX) + "|\\.pending)");

  private LogChunks() {}

  /** Whether a file id from the logs API is a valid file base: {@code driver} or a task id. */
  public static boolean isValidFileBase(String fileBase) {
    return fileBase != null && FILE_BASE.matcher(fileBase).matches();
  }

  /** Whether a directory under {@code LOG_DIR} is a process directory (named by a process id). */
  static boolean isProcessDir(Path dir) {
    return PROCESS_ID.matcher(dir.getFileName().toString()).matches();
  }

  /**
   * Pending chunks in a process directory, by file base and counter.
   *
   * @throws UncheckedIOException if the directory cannot be listed
   */
  static Map<String, TreeMap<Long, Path>> listPending(Path dir) {
    Map<String, TreeMap<Long, Path>> pending = new HashMap<>();
    if (!Files.isDirectory(dir)) {
      return pending;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + PENDING_SUFFIX)) {
      for (Path file : files) {
        Chunk chunk = parseChunk(file.getFileName().toString());
        if (chunk != null && chunk.pending) {
          pending.computeIfAbsent(chunk.fileBase, base -> new TreeMap<>()).put(chunk.counter, file);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list pending optimizing log chunks in " + dir, e);
    }
    return pending;
  }

  static String chunkName(String fileBase, long counter) {
    return String.format("%s-%06d%s", fileBase, counter, BUFFER_SUFFIX);
  }

  static String pendingName(String fileBase, long counter) {
    return String.format("%s-%06d%s", fileBase, counter, PENDING_SUFFIX);
  }

  /** File base of a local buffer name ({@code 3.log} gives {@code 3}), or null. */
  static String bufferBase(String name) {
    if (!name.endsWith(BUFFER_SUFFIX)) {
      return null;
    }
    String base = name.substring(0, name.length() - BUFFER_SUFFIX.length());
    return isValidFileBase(base) ? base : null;
  }

  /** Parses a chunk name ({@code <file>-<counter>.log} or {@code .pending}), or returns null. */
  static Chunk parseChunk(String name) {
    Matcher matcher = CHUNK.matcher(name);
    if (!matcher.matches()) {
      return null;
    }
    return new Chunk(
        matcher.group(1),
        Long.parseLong(matcher.group(2)),
        PENDING_SUFFIX.equals(matcher.group(3)));
  }

  static final class Chunk {
    final String fileBase;
    final long counter;
    final boolean pending;

    Chunk(String fileBase, long counter, boolean pending) {
      this.fileBase = fileBase;
      this.counter = counter;
      this.pending = pending;
    }
  }
}
