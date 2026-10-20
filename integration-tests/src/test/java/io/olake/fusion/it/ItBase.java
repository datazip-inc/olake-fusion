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
import java.util.ArrayList;
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

    /**
     * Source rows the CDC round updates and deletes. Together they are more than {@code
     * self-optimizing.major.trigger.duplicate-ratio} (0.1) of the seed sync's rows, which is what
     * makes a MAJOR rewrite the seed data file and its position deletes instead of leaving them.
     */
    private static final int CDC_UPDATES = SEED_ROWS / 2;

    private static final int CDC_DELETES = 3;

    private final SparkTable table;
    private final Olake olake;

    private final Path olakeLogs;
    private long nextSourceId = SEED_ROWS + 1;

    private TestTable(SparkTable table, Olake olake, Path olakeLogs) {
      this.table = table;
      this.olake = olake;
      this.olakeLogs = olakeLogs;
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
      TestTable test = new TestTable(table, olake, workDir.resolve("olake"));
      test.report();
      return test;
    }

    /** Names this table as the one the running test drives, for the run's report. */
    void report() {
      Report.table(table.namespace() + "." + table.name(), olakeLogs);
    }

    /**
     * Changes the target file size, as a user does in OLake-UI's table settings. Optimization stays
     * enabled.
     */
    void setTargetSize(long bytes) {
      fusion.setTableProperties(
          table.namespace(),
          table.name(),
          Map.of(TableProps.TARGET_SIZE, String.valueOf(bytes), TableProps.ENABLED, "true"));
    }

    // What a test reads of the table, through the user's query engine.

    String namespace() {
      return table.namespace();
    }

    String name() {
      return table.name();
    }

    /** The table as a query engine names it, for a failure message. */
    String ident() {
      return table.ident();
    }

    /** Live data and delete files of the current snapshot. */
    List<FileInfo> files() {
      return table.files();
    }

    /** Id of the snapshot the table points at. */
    String currentSnapshotId() {
      return table.currentSnapshotId();
    }

    /** Health score Fusion serves for this table, as OLake-UI's table list shows it. */
    long healthScore() {
      return fusion.healthScore(table.namespace(), table.name());
    }

    /** Time travels the table back to {@code snapshotId}. */
    void rollbackTo(String snapshotId) {
      table.rollbackTo(snapshotId);
    }

    /**
     * One CDC round in the source (updates, deletes, inserts) and an OLake sync: equality deletes
     * and one more data file.
     */
    void cdc() {
      olake.update(ids(1, CDC_UPDATES));
      olake.delete(ids(CDC_UPDATES + 1, CDC_DELETES));
      load(CDC_ROWS);
    }

    private static List<Long> ids(long first, int count) {
      List<Long> ids = new ArrayList<>(count);
      for (long id = first; id < first + count; id++) {
        ids.add(id);
      }
      return ids;
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

    /** Live rows read through the query engine, as sorted {@code id|region|payload} strings. */
    List<String> rows() {
      return table.rows();
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
    return optimize(t, null, types);
  }

  /**
   * As {@link #optimize}, and every process the crons produced in the meantime must be of {@code
   * onlyType}: what proves that a lower-priority cron that also fired did not run.
   */
  static Fusion.Process optimizeOnly(TestTable t, String onlyType, String... types) {
    return optimize(t, onlyType, types);
  }

  private static Fusion.Process optimize(TestTable t, String onlyType, String... types) {
    Set<String> known = fusion.processIds(t.namespace(), t.name());
    setCrons(t, types);
    Fusion.Process run;
    try {
      run = fusion.awaitFinishedRun(t.namespace(), t.name(), known, PROCESS_TIMEOUT);
    } finally {
      setCrons(t);
    }
    Report.process(run);
    if (onlyType != null) {
      List<Fusion.Process> all = fusion.newProcesses(t.namespace(), t.name(), known);
      for (Fusion.Process process : all) {
        assertEquals(onlyType, process.type(), "only " + onlyType + " may run, got " + all);
      }
    }
    Await.until(
        "Spark sees Fusion's newest snapshot of " + t.ident(),
        Duration.ofMinutes(2),
        () ->
            List.of(
                String.valueOf(t.currentSnapshotId()),
                String.valueOf(fusion.latestSnapshotId(t.namespace(), t.name()))),
        ids -> ids.get(0).equals(ids.get(1)));
    return run;
  }

  /**
   * Sets the cron of {@code type} to every minute, waits for the first new process to reach any
   * terminal status (including SKIPPED), sets every cron back to Never and returns that process.
   */
  static Fusion.Process cronFiresOnce(TestTable t, String type) {
    Set<String> known = fusion.processIds(t.namespace(), t.name());
    setCrons(t, type);
    try {
      Fusion.Process run =
          fusion.awaitProcess(
              t.namespace(), t.name(), known, PROCESS_TIMEOUT, Fusion.Process::terminal);
      Report.process(run);
      return run;
    } finally {
      setCrons(t);
    }
  }

  /**
   * OLake-UI's schedules: the crons of {@code types} every minute, the others Never. With no types,
   * every cron is Never. Optimization stays enabled, as the UI keeps it once a schedule is set.
   */
  private static void setCrons(TestTable t, String... types) {
    Set<String> every = Arrays.stream(types).map(TableProps::cronOf).collect(Collectors.toSet());
    Map<String, String> properties = new TreeMap<>();
    for (String cron :
        List.of(TableProps.MINOR_CRON, TableProps.MAJOR_CRON, TableProps.FULL_CRON)) {
      properties.put(cron, every.contains(cron) ? EVERY_MINUTE : TableProps.NEVER);
    }
    properties.put(TableProps.ENABLED, "true");
    fusion.setTableProperties(t.namespace(), t.name(), properties);
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
    List<String> actual = t.rows();
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

  /** Live data files of the current snapshot. */
  static long dataFiles(TestTable t) {
    return count(t.files(), SparkTable.DATA);
  }

  /** Live position delete files of the current snapshot. */
  static long positionDeletes(TestTable t) {
    return count(t.files(), SparkTable.POSITION_DELETES);
  }

  /** Live equality delete files of the current snapshot. */
  static long equalityDeletes(TestTable t) {
    return count(t.files(), SparkTable.EQUALITY_DELETES);
  }

  /** No equality delete is left: every one of them was applied or converted. */
  static void assertNoEqualityDeletes(TestTable t) {
    assertEquals(0, equalityDeletes(t), "equality deletes in " + t.files());
  }

  /** No delete file of either kind is left: every delete was merged into the data files. */
  static void assertNoDeleteFiles(TestTable t) {
    List<FileInfo> files = t.files();
    assertEquals(0, count(files, SparkTable.EQUALITY_DELETES), "equality deletes in " + files);
    assertEquals(0, count(files, SparkTable.POSITION_DELETES), "position deletes in " + files);
  }

  /** Setup precondition: the table holds exactly {@code expected} equality delete files. */
  static void requireEqualityDeletes(TestTable t, int expected) {
    List<FileInfo> files = t.files();
    long equality = count(files, SparkTable.EQUALITY_DELETES);
    if (equality != expected) {
      fail(
          "setup error: expected "
              + expected
              + " equality delete files before compaction, got "
              + equality
              + ": "
              + files);
    }
  }

  /** Setup precondition: the table holds at least one delete file. */
  static void requireDeleteFiles(TestTable t) {
    List<FileInfo> files = t.files();
    if (count(files, SparkTable.DATA) == files.size()) {
      fail("setup error: expected delete files before compaction: " + files);
    }
  }

  /**
   * Gives the table a target size under which every one of its data files is a full segment file,
   * so a MINOR converts their equality deletes to position deletes instead of rewriting the files
   * whole.
   *
   * <p>Fusion calls a data file a fragment below {@code target / fragment-ratio} and an undersized
   * segment up to {@code target * min-target-size-ratio}; only what is above that is a segment
   * file, and {@code segmentShouldRewritePos} (the conversion) is reached for segment files alone.
   * The smallest data file of the table is therefore target enough: it puts every file above {@code
   * target * 0.75} and far above {@code target / 8}.
   */
  static void useSegmentSizedFiles(TestTable t) {
    long smallest =
        t.files().stream()
            .filter(f -> f.content() == SparkTable.DATA)
            .mapToLong(FileInfo::size)
            .min()
            .orElseThrow(() -> new IllegalStateException("no data files in " + t.ident()));
    t.setTargetSize(smallest);
  }

  // Helpers

  private static long count(List<FileInfo> files, int content) {
    return files.stream().filter(f -> f.content() == content).count();
  }
}
