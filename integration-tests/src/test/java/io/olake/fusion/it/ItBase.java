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

package io.olake.fusion.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.olake.fusion.it.contract.Names;
import io.olake.fusion.it.contract.OptimizingType;
import io.olake.fusion.it.contract.TableProps;
import io.olake.fusion.it.harness.Await;
import io.olake.fusion.it.harness.Env;
import io.olake.fusion.it.harness.Fusion;
import io.olake.fusion.it.harness.Olake;
import io.olake.fusion.it.harness.SizeClass;
import io.olake.fusion.it.harness.SparkTable;
import io.olake.fusion.it.harness.SparkTable.FileInfo;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Stack precondition, the per-test table, and the few assertions every test shares. */
abstract class ItBase {
  /** Target size every test table is given, so test files stay small. */
  static final long TARGET_SIZE = 256 * 1024;

  static final String PARTITION_COLUMN = "region";
  static final String EVERY_MINUTE = "* * * * *";
  static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(10);

  private static final List<String> ALL_CRONS =
      List.of(TableProps.MINOR_CRON, TableProps.MAJOR_CRON, TableProps.FULL_CRON);

  static Fusion fusion;

  @BeforeAll
  static synchronized void stackPrecondition() {
    if (fusion != null) {
      return;
    }
    Fusion client = new Fusion(Env.FUSION_URL);
    assertEquals(200, client.login(), "login");
    assertFalse(client.currentUser().isNull(), "current user after login");
    assertTrue(
        client.resourceGroups().contains(Names.OPTIMIZER_GROUP),
        "resource groups contain " + Names.OPTIMIZER_GROUP);
    assertTrue(client.catalogs().contains(Names.CATALOG), "catalogs contain " + Names.CATALOG);
    Await.until(
        "a live optimizer in " + Names.OPTIMIZER_GROUP,
        Duration.ofMinutes(10),
        client::liveOptimizers,
        optimizers -> !optimizers.isEmpty());
    SparkTable.sql("SHOW NAMESPACES IN " + Names.CATALOG);
    fusion = client;
  }

  /** One test's table and the rows the test expects in it. */
  static final class TestTable {
    final SparkTable table;
    /** Null when only Spark writes the table. */
    final Olake olake;

    private final Map<Long, String> sparkRows = new TreeMap<>();
    private final Set<Long> deleted = new HashSet<>();
    private long nextSparkId = 1_000_000;

    private TestTable(SparkTable table, Olake olake) {
      this.table = table;
      this.olake = olake;
    }

    /** A table OLake creates from a fresh source DB seeded with ids 1..90. */
    static TestTable olake(String name, boolean partitioned) {
      Olake olake = new Olake(name, partitioned, Paths.get("target", "it-work", Env.RUN_ID, name));
      olake.createSource();
      olake.discover();
      SparkTable.recreateNamespace(olake.namespace());
      olake.insert(1, 90);
      olake.sync();
      SparkTable table =
          new SparkTable(olake.namespace(), Olake.TABLE, partitioned ? PARTITION_COLUMN : null);
      fusion.awaitTableListed(table.namespace(), table.name());
      table.setProperties(
          Map.of(
              TableProps.TARGET_SIZE,
              String.valueOf(TARGET_SIZE),
              "write.delete.mode",
              "merge-on-read"));
      return new TestTable(table, olake);
    }

    /** A table only Spark writes, in its own namespace. */
    static TestTable spark(String name, boolean partitioned) {
      String namespace = ("it_" + name + "_" + Env.RUN_ID).toLowerCase();
      SparkTable.recreateNamespace(namespace);
      SparkTable table =
          SparkTable.create(
              namespace,
              "t",
              partitioned ? PARTITION_COLUMN : null,
              Map.of(
                  TableProps.TARGET_SIZE,
                  String.valueOf(TARGET_SIZE),
                  "write.delete.mode",
                  "merge-on-read"));
      fusion.awaitTableListed(namespace, table.name());
      return new TestTable(table, null);
    }

    /** One CDC round in the source (updates, deletes, inserts) and an OLake sync. */
    void cdc() {
      olake.update(List.of(1L, 2L, 3L, 4L, 5L, 6L));
      olake.delete(List.of(7L, 8L, 9L));
      olake.insert(91, 30);
      olake.sync();
    }

    /** Appends {@code files} Spark files of {@code size} to the region and checks their band. */
    void append(String region, SizeClass size, int files) {
      for (int i = 0; i < files; i++) {
        Set<String> before = paths(table.files());
        long first = nextSparkId;
        table.append(region, first, size.rows());
        nextSparkId += size.rows();
        for (long id = first; id < nextSparkId; id++) {
          sparkRows.put(id, id + "|" + region + "|" + SparkTable.payloadOf(id));
        }
        for (FileInfo file : table.files()) {
          if (!before.contains(file.path()) && SizeClass.of(file.size(), TARGET_SIZE) != size) {
            fail("setup error: Spark wrote " + file.size() + " bytes, outside " + size);
          }
        }
      }
    }

    /** Deletes 3 live rows of the region through Spark (position deletes). */
    void deleteRows(String region) {
      List<Long> ids =
          expectedRows().stream()
              .filter(r -> r.split("\\|")[1].equals(region))
              .map(r -> Long.parseLong(r.substring(0, r.indexOf('|'))))
              .limit(3)
              .collect(Collectors.toList());
      table.deleteIds(ids);
      deleted.addAll(ids);
    }

    /** Source rows (if OLake writes) + rows Spark appended - rows Spark deleted. */
    List<String> expectedRows() {
      TreeSet<String> rows = new TreeSet<>(olake == null ? List.of() : olake.rows());
      rows.addAll(sparkRows.values());
      rows.removeIf(r -> deleted.contains(Long.parseLong(r.substring(0, r.indexOf('|')))));
      return List.copyOf(rows);
    }

    /** Partition name of a region ({@code ""} when unpartitioned). */
    String partition(String region) {
      return table.partitioned() ? PARTITION_COLUMN + "=" + region : "";
    }
  }

  /**
   * What a user does to compact: sets the crons of {@code types} to every minute, waits for the
   * first process that did work and finished, removes the crons. Returns that process once Spark
   * sees Fusion's newest snapshot.
   */
  static Fusion.Process optimize(TestTable t, String... types) {
    SparkTable table = t.table;
    Set<String> known = fusion.processIds(table.namespace(), table.name());
    Map<String, String> crons =
        Arrays.stream(types).collect(Collectors.toMap(TableProps::cronOf, type -> EVERY_MINUTE));
    table.setProperties(crons);
    Fusion.Process run;
    try {
      run = fusion.awaitFinishedRun(table.namespace(), table.name(), known, PROCESS_TIMEOUT);
    } finally {
      table.removeProperties(ALL_CRONS);
    }
    Await.until(
        "Spark sees Fusion's newest snapshot of " + table.ident(),
        Duration.ofMinutes(2),
        () ->
            List.of(
                String.valueOf(table.currentSnapshotId()),
                String.valueOf(fusion.latestSnapshotId(table.namespace(), table.name()))),
        ids -> ids.get(0).equals(ids.get(1)));
    return run;
  }

  /**
   * Sets the cron of {@code type} to every minute, waits for the first new process to reach any
   * terminal status (including SKIPPED), removes the crons and returns that process.
   */
  static Fusion.Process cronFiresOnce(TestTable t, String type) {
    SparkTable table = t.table;
    Set<String> known = fusion.processIds(table.namespace(), table.name());
    table.setProperties(Map.of(TableProps.cronOf(type), EVERY_MINUTE));
    try {
      return fusion.awaitProcess(
          table.namespace(), table.name(), known, PROCESS_TIMEOUT, Fusion.Process::terminal);
    } finally {
      table.removeProperties(ALL_CRONS);
    }
  }

  // Assertions

  static void assertProcess(Fusion.Process run, String type, String status) {
    assertEquals(type, run.type(), "type of " + run);
    assertEquals(status, run.status(), "status of " + run);
  }

  /**
   * Live rows read through Spark equal the rows the test wrote. The comparison is on the full row
   * list, so a duplicated row also fails it.
   */
  static void assertRows(TestTable t) {
    List<String> expected = t.expectedRows();
    List<String> actual = t.table.rows();
    if (!expected.equals(actual)) {
      Set<String> missing = new TreeSet<>(expected);
      missing.removeAll(actual);
      Set<String> unexpected = new TreeSet<>(actual);
      unexpected.removeAll(expected);
      fail(
          "rows differ: expected "
              + expected.size()
              + ", got "
              + actual.size()
              + "; missing "
              + missing.size()
              + ", unexpected "
              + unexpected.size());
    }
  }

  /** Fusion's files tab lists exactly the files Spark's {@code $files} lists. */
  static void assertFusionAgrees(TestTable t) {
    SparkTable table = t.table;
    Set<String> engine =
        table.files().stream()
            .map(f -> f.path() + " [" + f.partition() + "] " + fileType(f.content()))
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> tab =
        fusion.files(table.namespace(), table.name(), table.partitioned()).stream()
            .map(f -> f.path() + " [" + f.partition() + "] " + f.fileType())
            .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(engine, tab, "Fusion's files tab == engine's files table");
  }

  /**
   * File layout after {@code type} in each partition (design decision 12): MINOR and MAJOR leave no
   * equality deletes; FULL leaves no delete files and one data file when the partition held less
   * than the target size before the run ({@code before}).
   */
  static void assertFiles(TestTable t, String type, List<FileInfo> before, String... partitions) {
    List<FileInfo> after = t.table.files();
    for (String partition : partitions) {
      List<FileInfo> files = inPartition(after, partition);
      assertEquals(
          0, count(files, SparkTable.EQUALITY_DELETES), "equality deletes in [" + partition + "]");
      if (OptimizingType.FULL.equals(type)) {
        assertEquals(
            0,
            count(files, SparkTable.POSITION_DELETES),
            "position deletes in [" + partition + "]");
        long total = inPartition(before, partition).stream().mapToLong(FileInfo::size).sum();
        if (total < TARGET_SIZE) {
          assertEquals(1, count(files, SparkTable.DATA), "data files in [" + partition + "]");
        }
      }
    }
  }

  /** Setup precondition: each partition holds at least one delete file / no delete file. */
  static void requireDeletes(TestTable t, boolean present, String... partitions) {
    for (String partition : partitions) {
      List<FileInfo> files = inPartition(t.table.files(), partition);
      boolean has = count(files, SparkTable.DATA) < files.size();
      if (has != present) {
        fail(
            "setup error: ["
                + partition
                + "] expected "
                + (present ? "" : "no ")
                + "delete files before compaction: "
                + files);
      }
    }
  }

  // Helpers

  static List<FileInfo> inPartition(List<FileInfo> files, String partition) {
    return files.stream().filter(f -> f.partition().equals(partition)).collect(Collectors.toList());
  }

  private static long count(List<FileInfo> files, int content) {
    return files.stream().filter(f -> f.content() == content).count();
  }

  private static Set<String> paths(List<FileInfo> files) {
    return files.stream().map(FileInfo::path).collect(Collectors.toSet());
  }

  private static String fileType(int content) {
    switch (content) {
      case SparkTable.DATA:
        return Names.DATA_FILE;
      case SparkTable.POSITION_DELETES:
        return Names.POS_DELETE_FILE;
      case SparkTable.EQUALITY_DELETES:
        return Names.EQ_DELETE_FILE;
      default:
        throw new IllegalArgumentException("content " + content);
    }
  }
}
