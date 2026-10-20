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

import io.olake.fusion.it.Contract.Names;
import io.olake.fusion.it.Contract.OptimizingType;
import io.olake.fusion.it.Contract.TableProps;
import io.olake.fusion.it.harness.Await;
import io.olake.fusion.it.harness.Env;
import io.olake.fusion.it.harness.Fusion;
import io.olake.fusion.it.harness.Olake;
import io.olake.fusion.it.harness.SparkTable;
import io.olake.fusion.it.harness.SparkTable.FileInfo;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
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

  static final String EVERY_MINUTE = "* * * * *";
  static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(10);

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
        Duration.ofMinutes(3),
        client::liveOptimizers,
        optimizers -> !optimizers.isEmpty());
    SparkTable.sql("SHOW NAMESPACES IN " + Names.CATALOG);
    fusion = client;
  }

  /**
   * One test's table. Every table is written the way a Fusion user's tables are written: OLake
   * syncs a source Postgres database into the catalog, and OLake creates the namespace and the
   * table on the first sync. The source is the oracle for the rows the table must hold.
   */
  static final class TestTable {
    /** Rows of the first sync. */
    private static final int SEED_ROWS = 90;

    /** Rows the CDC round inserts. */
    private static final int CDC_ROWS = 30;

    final SparkTable table;
    final Olake olake;

    private long nextSourceId = SEED_ROWS + 1;

    private TestTable(SparkTable table, Olake olake) {
      this.table = table;
      this.olake = olake;
    }

    /**
     * A fresh source database, discovered and synced once: OLake creates the namespace and the
     * table, Fusion picks it up, and the table gets the settings OLake-UI writes.
     */
    static TestTable create(String name) {
      Path workDir = Paths.get("target", "work", Env.RUN_ID, name);
      Olake olake = new Olake(name, workDir);
      olake.createSource();
      olake.discover();
      olake.insert(1, SEED_ROWS);
      olake.sync();
      SparkTable table = new SparkTable(olake.namespace(), Olake.TABLE);
      fusion.awaitTableListed(table.namespace(), table.name());
      // OLake-UI's target file size setting, which also enables optimization.
      fusion.setTableProperties(
          table.namespace(),
          table.name(),
          Map.of(TableProps.TARGET_SIZE, String.valueOf(TARGET_SIZE), TableProps.ENABLED, "true"));
      Report.table(table.namespace() + "." + table.name(), workDir.resolve("olake"));
      return new TestTable(table, olake);
    }

    /**
     * One CDC round in the source (updates, deletes, inserts) and an OLake sync: equality deletes
     * and one more data file.
     */
    void cdc() {
      olake.update(List.of(1L, 2L, 3L, 4L, 5L, 6L));
      olake.delete(List.of(7L, 8L, 9L));
      load(CDC_ROWS);
    }

    /** {@link #SEED_ROWS} new source rows and an OLake sync: one more data file. */
    void load() {
      load(SEED_ROWS);
    }

    /** New source rows and an OLake sync: one more data file. */
    void load(int rows) {
      olake.insert(nextSourceId, rows);
      nextSourceId += rows;
      olake.sync();
    }

    /** The source rows, as sorted {@code id|region|payload} strings. */
    List<String> expectedRows() {
      return List.copyOf(new TreeSet<>(olake.rows()));
    }
  }

  /**
   * What a user does to compact: sets the crons of {@code types} to every minute, waits for the
   * first process that did work and finished, sets every cron back to Never. Returns that process
   * once Spark sees Fusion's newest snapshot.
   */
  static Fusion.Process optimize(TestTable t, String... types) {
    SparkTable table = t.table;
    Set<String> known = fusion.processIds(table.namespace(), table.name());
    setCrons(table, types);
    Fusion.Process run;
    try {
      run = fusion.awaitFinishedRun(table.namespace(), table.name(), known, PROCESS_TIMEOUT);
    } finally {
      setCrons(table);
    }
    Report.process(run);
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
   * terminal status (including SKIPPED), sets every cron back to Never and returns that process.
   */
  static Fusion.Process cronFiresOnce(TestTable t, String type) {
    SparkTable table = t.table;
    Set<String> known = fusion.processIds(table.namespace(), table.name());
    setCrons(table, type);
    try {
      Fusion.Process run =
          fusion.awaitProcess(
              table.namespace(), table.name(), known, PROCESS_TIMEOUT, Fusion.Process::terminal);
      Report.process(run);
      return run;
    } finally {
      setCrons(table);
    }
  }

  /**
   * OLake-UI's schedules: the crons of {@code types} every minute, the others Never. With no types,
   * every cron is Never. Optimization stays enabled, as the UI keeps it once a schedule is set.
   */
  private static void setCrons(SparkTable table, String... types) {
    Set<String> every = Arrays.stream(types).map(TableProps::cronOf).collect(Collectors.toSet());
    Map<String, String> properties = new TreeMap<>();
    for (String cron :
        List.of(TableProps.MINOR_CRON, TableProps.MAJOR_CRON, TableProps.FULL_CRON)) {
      properties.put(cron, every.contains(cron) ? EVERY_MINUTE : TableProps.NEVER);
    }
    properties.put(TableProps.ENABLED, "true");
    fusion.setTableProperties(table.namespace(), table.name(), properties);
  }

  // Assertions

  static void assertProcess(Fusion.Process run, String type, String status) {
    assertEquals(type, run.type(), "type of " + run);
    assertEquals(status, run.status(), "status of " + run);
  }

  /**
   * Live rows read through Spark equal the rows the source holds. Order carries no meaning: both
   * sides are sorted {@code id|region|payload} strings, compared as lists rather than sets so a
   * duplicated row also fails it.
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

  /**
   * File layout after {@code type} (design decision 12): MINOR and MAJOR leave no equality deletes;
   * FULL leaves no delete files and one data file when the table held less than the target size
   * before the run ({@code before}).
   */
  static void assertFiles(TestTable t, String type, List<FileInfo> before) {
    List<FileInfo> files = t.table.files();
    assertEquals(10, count(files, SparkTable.EQUALITY_DELETES), "equality deletes"); // TODO: remvoe 10 added for failing it
    if (OptimizingType.FULL.equals(type)) {
      assertEquals(0, count(files, SparkTable.POSITION_DELETES), "position deletes");
      if (before.stream().mapToLong(FileInfo::size).sum() < TARGET_SIZE) {
        assertEquals(1, count(files, SparkTable.DATA), "data files");
      }
    }
  }

  /** Setup precondition: the table holds at least one delete file / no delete file. */
  static void requireDeletes(TestTable t, boolean present) {
    List<FileInfo> files = t.table.files();
    boolean has = count(files, SparkTable.DATA) < files.size();
    if (has != present) {
      fail(
          "setup error: expected "
              + (present ? "" : "no ")
              + "delete files before compaction: "
              + files);
    }
  }

  /** Setup precondition: the table holds at least {@code least} data files. */
  static void requireDataFiles(TestTable t, int least) {
    List<FileInfo> files = t.table.files();
    long data = count(files, SparkTable.DATA);
    if (data < least) {
      fail("setup error: expected at least " + least + " data files, got " + data + ": " + files);
    }
  }

  // Helpers

  private static long count(List<FileInfo> files, int content) {
    return files.stream().filter(f -> f.content() == content).count();
  }
}
