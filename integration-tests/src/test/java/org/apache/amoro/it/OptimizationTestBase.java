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

package org.apache.amoro.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.amoro.it.support.FusionStack;
import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.IcebergFixture.WrittenDataFile;
import org.apache.amoro.it.support.StackEnv;
import org.apache.amoro.it.support.TableLayout;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared setup for optimization tests. Each test builds an exact file layout through the Iceberg
 * API with no cron set, records the expected row set, then enables the cron of the one optimizing
 * type it verifies and waits for the resulting process.
 */
abstract class OptimizationTestBase {

  /** Rows per file that land in each size class with {@link IcebergFixture#TARGET_SIZE}. */
  static final int FRAGMENT_ROWS = 50;

  static final int UNDERSIZED_SEGMENT_ROWS = 1200;
  static final int TARGET_REACHED_ROWS = 4000;

  static IcebergFixture iceberg;

  /** All rows written to the table under test, in id order. */
  final List<Record> written = new ArrayList<>();

  private long nextId = 1;

  @BeforeAll
  static void setUpStack() {
    FusionStack.ensureOptimizerRunning();
    iceberg = IcebergFixture.shared();
  }

  static Map<String, String> optimizationProperties() {
    Map<String, String> props = new HashMap<>();
    props.put("self-optimizing.target-size", String.valueOf(IcebergFixture.TARGET_SIZE));
    return props;
  }

  static int partitions(Layout layout) {
    return layout == Layout.PARTITIONED ? IcebergFixture.REGIONS.length : 1;
  }

  /**
   * Appends {@code rowsPerPartition} rows to every partition of the layout in one commit and
   * returns the written files, one per partition.
   */
  List<WrittenDataFile> appendFiles(Table table, Layout layout, int rowsPerPartition) {
    int count = rowsPerPartition * partitions(layout);
    List<Record> rows = IcebergFixture.rows(nextId, count);
    nextId += count;
    written.addAll(rows);
    return iceberg.append(table, rows);
  }

  /**
   * Appends {@code rows} rows to the {@code region} partition only, in one commit, and returns the
   * single file written. Region skew lets partitions qualify for different optimizing types.
   */
  List<WrittenDataFile> appendToRegion(Table table, String region, int rows) {
    int span = rows * IcebergFixture.REGIONS.length;
    List<Record> regionRows = new ArrayList<>(rows);
    for (Record record : IcebergFixture.rows(nextId, span)) {
      if (region.equals(record.getField("region"))) {
        regionRows.add(record);
      }
    }
    nextId += span;
    written.addAll(regionRows);
    return iceberg.append(table, regionRows);
  }

  /** Enables the cron for {@code optimizingType} and waits for the process it triggers. */
  static JsonNode runOptimization(Table table, String name, String optimizingType) {
    return runOptimizationWithCrons(table, name, optimizingType);
  }

  /**
   * Enables the crons of all {@code optimizingTypes} in one commit and waits for the first process
   * that is not SKIPPED, asserting nothing else ran before it.
   */
  static JsonNode runOptimizationWithCrons(Table table, String name, String... optimizingTypes) {
    FusionStack.awaitTableListed(StackEnv.TEST_DB, name);
    Set<String> known = FusionStack.processIds(name);
    FusionStack.enableCrons(table, optimizingTypes);
    JsonNode process = FusionStack.awaitFinishedOptimization(name, known);
    assertNothingRanBefore(name, known, process);
    return process;
  }

  /** Every new process that started before {@code process} must be a SKIPPED record. */
  static void assertNothingRanBefore(String name, Set<String> known, JsonNode process) {
    long start = process.path("startTime").asLong();
    for (JsonNode other : FusionStack.newProcesses(name, known)) {
      if (other.path("startTime").asLong() < start) {
        assertEquals(
            "SKIPPED",
            FusionStack.status(other),
            "ran before " + FusionStack.describe(process) + ": " + FusionStack.describe(other));
      }
    }
  }

  /** Data files in the {@code region} partition of a partitioned table, 0 when it has none. */
  static int dataFilesIn(TableLayout layout, String region) {
    int[] counts = layout.filesPerPartition().get("region=" + region);
    return counts == null ? 0 : counts[0];
  }

  static void assertSucceededAs(JsonNode process, String optimizingType) {
    assertEquals("SUCCESS", FusionStack.status(process), FusionStack.describe(process));
    assertEquals(optimizingType, FusionStack.type(process), FusionStack.describe(process));
  }

  /** Guards the size classes the scenario depends on, so a sizing drift fails loudly. */
  static void assertSizeBetween(List<WrittenDataFile> files, long minExclusive, long maxInclusive) {
    for (WrittenDataFile file : files) {
      DataFile dataFile = file.file();
      assertTrue(
          dataFile.fileSizeInBytes() > minExclusive && dataFile.fileSizeInBytes() <= maxInclusive,
          String.format(
              "fixture sizing: %s is %d bytes, expected (%d, %d]",
              dataFile.location(), dataFile.fileSizeInBytes(), minExclusive, maxInclusive));
    }
  }

  static void assertSamePartitions(TableLayout before, TableLayout after) {
    assertEquals(
        before.filesPerPartition().keySet(),
        after.filesPerPartition().keySet(),
        "partitions after optimization; before=" + before + " after=" + after);
  }
}
