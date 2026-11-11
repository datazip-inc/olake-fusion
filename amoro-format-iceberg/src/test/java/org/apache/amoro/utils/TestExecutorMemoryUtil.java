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

import org.apache.amoro.utils.ExecutorMemoryUtil.HeapLevel;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestExecutorMemoryUtil {

  private static final long MIB = 1L << 20;
  private static final long GIB = 1L << 30;

  private static Record stringKey(String value) {
    Record key =
        GenericRecord.create(
            new Schema(Types.NestedField.required(1, "key", Types.StringType.get())));
    key.setField("key", value);
    return key;
  }

  @Test
  public void changedKeysAreCappedByDataRows() {
    Assertions.assertEquals(100, ExecutorMemoryUtil.changedKeysPerTask(100, 500, true));
  }

  @Test
  public void changedKeysAreCappedByDeleteRows() {
    Assertions.assertEquals(100, ExecutorMemoryUtil.changedKeysPerTask(500, 100, true));
  }

  @Test
  public void changedKeysAreZeroWithoutDataOrDeleteRows() {
    Assertions.assertEquals(0, ExecutorMemoryUtil.changedKeysPerTask(0, 500, true));
    Assertions.assertEquals(0, ExecutorMemoryUtil.changedKeysPerTask(500, 0, true));
  }

  @Test
  public void withoutBloomFilterEveryDeleteRowIsAChangedKey() {
    Assertions.assertEquals(500, ExecutorMemoryUtil.changedKeysPerTask(100, 500, false));
    Assertions.assertEquals(100, ExecutorMemoryUtil.changedKeysPerTask(500, 100, false));
  }

  @Test
  public void keyLengthCountsCharactersOfAStringKey() {
    Assertions.assertEquals(5, ExecutorMemoryUtil.keyLength(stringKey("abcde")));
  }

  @Test
  public void keyLengthAddsAllStringColumns() {
    Record key =
        GenericRecord.create(
            new Schema(
                Types.NestedField.required(1, "tenant", Types.StringType.get()),
                Types.NestedField.required(2, "region", Types.StringType.get())));
    key.setField("tenant", "acme");
    key.setField("region", "eu");
    Assertions.assertEquals(6, ExecutorMemoryUtil.keyLength(key));
  }

  @Test
  public void keyLengthIgnoresNonStringColumns() {
    Record key =
        GenericRecord.create(
            new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get())));
    key.setField("id", 42L);
    key.setField("name", "abc");
    Assertions.assertEquals(3, ExecutorMemoryUtil.keyLength(key));
  }

  @Test
  public void keyLengthIgnoresNullColumns() {
    Record key =
        GenericRecord.create(
            new Schema(Types.NestedField.optional(1, "name", Types.StringType.get())));
    Assertions.assertEquals(0, ExecutorMemoryUtil.keyLength(key));
  }

  @Test
  public void bytesPerKeyIsTheBasePlusKeyLength() {
    Assertions.assertEquals(ExecutorMemoryUtil.BASE_KEY_BYTES, ExecutorMemoryUtil.bytesPerKey(0));
    Assertions.assertEquals(
        ExecutorMemoryUtil.BASE_KEY_BYTES + 40, ExecutorMemoryUtil.bytesPerKey(40));
  }

  @Test
  public void memoryNeededCoversChangedKeysAndLargestDeleteFileWithBuffer() {
    Assertions.assertEquals(
        (long) ((1000 + 500) * 300 * ExecutorMemoryUtil.BUFFER),
        ExecutorMemoryUtil.memoryNeededBytes(1000, 500, 300));
  }

  @Test
  public void memoryNeededIsZeroWithoutKeys() {
    Assertions.assertEquals(0, ExecutorMemoryUtil.memoryNeededBytes(0, 0, 300));
  }

  @Test
  public void recommendedExecutorMemoryRoundsUpToAWholeGb() {
    Assertions.assertEquals(1, ExecutorMemoryUtil.recommendedExecutorMemoryGb(GIB));
    Assertions.assertEquals(2, ExecutorMemoryUtil.recommendedExecutorMemoryGb(GIB + 1));
  }

  @Test
  public void recommendedExecutorMemoryIsAtLeast1Gb() {
    Assertions.assertEquals(1, ExecutorMemoryUtil.recommendedExecutorMemoryGb(0));
  }

  @Test
  public void formatGbShowsTwoDecimals() {
    Assertions.assertEquals("0.50g", ExecutorMemoryUtil.formatGb(GIB / 2));
    Assertions.assertEquals("3.00g", ExecutorMemoryUtil.formatGb(3 * GIB));
  }

  @Test
  public void formatExecutorMemoryRoundsTheHeapToTheNearestHalfGb() {
    Assertions.assertEquals("1g", ExecutorMemoryUtil.formatExecutorMemory(989 * MIB));
    Assertions.assertEquals("1.5g", ExecutorMemoryUtil.formatExecutorMemory(1536 * MIB));
    Assertions.assertEquals("3g", ExecutorMemoryUtil.formatExecutorMemory(3 * GIB));
  }

  @Test
  public void heapLevelIsOkUpTo80PercentOfTheHeap() {
    long heap = 1000 * MIB;
    Assertions.assertEquals(HeapLevel.OK, ExecutorMemoryUtil.heapLevel(800 * MIB, heap));
  }

  @Test
  public void heapLevelIsNearlyFullFrom80PercentUpToTheHeap() {
    long heap = 1000 * MIB;
    Assertions.assertEquals(
        HeapLevel.NEARLY_FULL, ExecutorMemoryUtil.heapLevel(800 * MIB + 1, heap));
    Assertions.assertEquals(HeapLevel.NEARLY_FULL, ExecutorMemoryUtil.heapLevel(heap, heap));
  }

  @Test
  public void heapLevelIsTooSmallAboveTheHeap() {
    long heap = 1000 * MIB;
    Assertions.assertEquals(HeapLevel.TOO_SMALL, ExecutorMemoryUtil.heapLevel(heap + 1, heap));
  }

  @Test
  public void overheadUsesTheFactor() {
    Assertions.assertEquals(1000, ExecutorMemoryUtil.overheadMb(10_000, null, 0.10));
  }

  @Test
  public void overheadIsAtLeastTheMinimum() {
    Assertions.assertEquals(
        ExecutorMemoryUtil.MIN_OVERHEAD_MB, ExecutorMemoryUtil.overheadMb(1000, null, 0.10));
  }

  @Test
  public void explicitOverheadOverridesFactorAndMinimum() {
    Assertions.assertEquals(100, ExecutorMemoryUtil.overheadMb(10_000, 100L, 0.10));
  }

  @Test
  public void recommendedOverheadUsesTheHigherFactorUpToTheLimit() {
    long limit = ExecutorMemoryUtil.RECOMMENDED_OVERHEAD_UP_TO_MB;
    Assertions.assertEquals(
        (long) (limit * ExecutorMemoryUtil.RECOMMENDED_OVERHEAD_FACTOR),
        ExecutorMemoryUtil.recommendedOverheadMb(limit));
  }

  @Test
  public void recommendedOverheadUsesTheDefaultFactorAboveTheLimit() {
    long aboveLimit = ExecutorMemoryUtil.RECOMMENDED_OVERHEAD_UP_TO_MB + 1000;
    Assertions.assertEquals(
        (long) (aboveLimit * ExecutorMemoryUtil.DEFAULT_OVERHEAD_FACTOR),
        ExecutorMemoryUtil.recommendedOverheadMb(aboveLimit));
  }

  @Test
  public void recommendedOverheadIs512mBelow4g() {
    Assertions.assertEquals(512, ExecutorMemoryUtil.recommendedOverheadMb(1024));
    Assertions.assertEquals(512, ExecutorMemoryUtil.recommendedOverheadMb(2048));
    Assertions.assertEquals(512, ExecutorMemoryUtil.recommendedOverheadMb(3072));
  }

  @Test
  public void recommendedOverheadIs15PercentFrom4gTo6g() {
    Assertions.assertEquals(614, ExecutorMemoryUtil.recommendedOverheadMb(4096));
    Assertions.assertEquals(921, ExecutorMemoryUtil.recommendedOverheadMb(6144));
  }

  @Test
  public void recommendedOverheadFactorIsTheDefaultAbove6g() {
    Assertions.assertEquals(0.15, ExecutorMemoryUtil.recommendedOverheadFactor(6144));
    Assertions.assertEquals(
        ExecutorMemoryUtil.DEFAULT_OVERHEAD_FACTOR,
        ExecutorMemoryUtil.recommendedOverheadFactor(8192));
  }
}
