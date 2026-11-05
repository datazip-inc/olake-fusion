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

package org.apache.amoro.utils;

import org.apache.iceberg.StructLike;

import java.util.Locale;

/**
 * Executor memory a compaction task needs, from the formulas in the compaction out-of-memory docs:
 * the heap for its equality-delete keys ({@link #MEMORY_NEEDED_DOC_URL}) and the pod's memory
 * overhead on Kubernetes ({@link #MEMORY_OVERHEAD_DOC_URL}).
 */
public final class ExecutorMemoryUtil {

  private static final String DOC_URL =
      "https://olake.io/docs/fusion/maintenance/troubleshooting/out-of-memory/";
  public static final String MEMORY_NEEDED_DOC_URL = DOC_URL + "#how-much-memory-you-need";
  public static final String MEMORY_OVERHEAD_DOC_URL =
      DOC_URL + "#4-problem-b-the-pod-went-over-its-memory-limit";

  // Heap per equality-delete key, before the key's own characters
  public static final long BASE_KEY_BYTES = 250L;
  // Key length assumed when it isn't measured: the length of OLake Go's _olake_id hash
  public static final int DEFAULT_KEY_LENGTH = 32;
  // Headroom for Spark and Java themselves
  public static final double BUFFER = 1.3;
  // Above this share of the heap the executor memory is reported as nearly full
  public static final double WARN_HEAP_FRACTION = 0.8;

  // Spark's memoryOverhead defaults
  public static final long MIN_OVERHEAD_MB = 384L;
  public static final double DEFAULT_OVERHEAD_FACTOR = 0.10;
  // Executors of RECOMMENDED_OVERHEAD_UP_TO_MB or less need this share as overhead
  public static final double RECOMMENDED_OVERHEAD_FACTOR = 0.15;
  public static final long RECOMMENDED_OVERHEAD_UP_TO_MB = 6144L;

  private static final long GIB = 1L << 30;

  /** How a task's memory need compares with the executor heap. */
  public enum HeapLevel {
    OK,
    NEARLY_FULL,
    TOO_SMALL
  }

  private ExecutorMemoryUtil() {}

  /**
   * Characters in a key: the sum over its string columns. Other column types add nothing, since
   * {@link #BASE_KEY_BYTES} covers them.
   */
  public static int keyLength(StructLike key) {
    int length = 0;
    for (int i = 0; i < key.size(); i++) {
      Object value = key.get(i, Object.class);
      if (value instanceof CharSequence) {
        length += ((CharSequence) value).length();
      }
    }
    return length;
  }

  /** Bytes per key: {@link #BASE_KEY_BYTES} plus the longest key's characters. */
  public static long bytesPerKey(int maxKeyLength) {
    return BASE_KEY_BYTES + maxKeyLength;
  }

  /** The most changed keys a task can hold: one per data row, and at most one per delete row. */
  public static long changedKeysPerTask(long dataRows, long eqDeleteRows) {
    return Math.min(dataRows, eqDeleteRows);
  }

  /**
   * Heap a task needs: its changed keys plus the largest equality-delete file, which is read whole
   * while those keys are held.
   */
  public static long memoryNeededBytes(
      long changedKeysPerTask, long largestEqDeleteFileRecordCnt, long bytesPerKey) {
    return (long) ((changedKeysPerTask + largestEqDeleteFileRecordCnt) * bytesPerKey * BUFFER);
  }

  /** {@code spark.executor.memory} in whole GB that fits the given need, at least 1. */
  public static long recommendedExecutorMemoryGb(long memoryNeededBytes) {
    return Math.max(1, (memoryNeededBytes + GIB - 1) / GIB);
  }

  /** Bytes as gigabytes with two decimals, for example {@code 2.81g}. */
  public static String formatGb(long bytes) {
    return String.format(Locale.ROOT, "%.2fg", bytes / (double) GIB);
  }

  /**
   * The {@code spark.executor.memory} a heap of this size comes from, to the nearest 0.5 GB: the
   * JVM reports slightly less than the setting, for example 989 MB for {@code 1g}.
   */
  public static String formatExecutorMemory(long heapBytes) {
    double gb = Math.round(heapBytes * 2.0 / GIB) / 2.0;
    return gb == Math.rint(gb)
        ? String.format(Locale.ROOT, "%.0fg", gb)
        : String.format(Locale.ROOT, "%.1fg", gb);
  }

  public static HeapLevel heapLevel(long memoryNeededBytes, long maxHeapBytes) {
    if (memoryNeededBytes > maxHeapBytes) {
      return HeapLevel.TOO_SMALL;
    }
    if (memoryNeededBytes > maxHeapBytes * WARN_HEAP_FRACTION) {
      return HeapLevel.NEARLY_FULL;
    }
    return HeapLevel.OK;
  }

  /**
   * The memory overhead Spark gives each executor pod, in MB: {@code explicitOverheadMb} when set,
   * otherwise {@code overheadFactor} of the executor memory with Spark's 384 MB minimum.
   */
  public static long overheadMb(
      long executorMemoryMb, Long explicitOverheadMb, double overheadFactor) {
    if (explicitOverheadMb != null) {
      return explicitOverheadMb;
    }
    return Math.max(MIN_OVERHEAD_MB, (long) (executorMemoryMb * overheadFactor));
  }

  /** The smallest memory overhead to use for an executor of this size, in MB. */
  public static long recommendedOverheadMb(long executorMemoryMb) {
    double factor =
        executorMemoryMb <= RECOMMENDED_OVERHEAD_UP_TO_MB
            ? RECOMMENDED_OVERHEAD_FACTOR
            : DEFAULT_OVERHEAD_FACTOR;
    return Math.max(MIN_OVERHEAD_MB, (long) (executorMemoryMb * factor));
  }
}
