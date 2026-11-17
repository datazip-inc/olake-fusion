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

import io.olake.fusion.it.harness.Env;
import io.olake.fusion.it.harness.Fusion;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What the run did, printed as one table when the tests in this JVM are over: every test with its
 * result, how long it took, the table it compacted and the optimizing processes it drove. A failed
 * test also gets its message and the path of every log that covers it.
 *
 * <p>The tests record their own evidence as they go ({@link #table}, {@link #process}), because
 * only they know which process belongs to which table. The alternative, reading failsafe's XML
 * afterwards, cannot see any of it.
 *
 * <p>Failsafe runs the parallel tests and the serial ones in two JVMs, so two tables are printed,
 * one per lane.
 */
public final class Report
    implements BeforeEachCallback, TestWatcher, ExtensionContext.Store.CloseableResource {

  /** Where AMS writes the per-process optimizing logs, mounted from the host at /mnt/amoro-logs. */
  private static final Path COMPACTION_LOGS = Path.of(Env.LOG_DIR, "compaction");

  /**
   * One directory per test under the directory the run collects and CI uploads, holding every log
   * that covers that test: its OLake syncs and the optimizer's logs for each process it drove. A
   * failure is then one folder, not a hunt across three trees.
   */
  private static final Path COLLECTED = Path.of("target", "logs", "tests");

  private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
  private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

  /** When the first test of this JVM started, for the lane's wall time. */
  private static volatile long laneStarted;

  /** One test's row. */
  private static final class Entry {
    private final String test;
    private final long started = System.currentTimeMillis();
    private final List<Fusion.Process> processes = new ArrayList<>();
    private String table = "";
    private Path olakeLogs;
    private Path collected;
    private long took;
    private String failure;

    Entry(String test) {
      this.test = test;
    }

    String processSummary() {
      if (processes.isEmpty()) {
        return "-";
      }
      return processes.stream().map(Entry::describe).collect(Collectors.joining(", "));
    }

    /** {@code MINOR SUCCESS (1 task)}: what Fusion did, in the words its API uses. */
    private static String describe(Fusion.Process process) {
      long tasks = process.totalTasks();
      return process.type()
          + " "
          + process.status()
          + " ("
          + tasks
          + (tasks == 1 ? " task)" : " tasks)");
    }
  }

  // Recorded by the tests

  /** The table a test owns, and where its OLake sync logs are written. */
  static void table(String ident, Path olakeLogs) {
    Entry entry = CURRENT.get();
    if (entry != null) {
      entry.table = ident;
      entry.olakeLogs = olakeLogs;
    }
  }

  /** An optimizing process a test drove, in the order it ran them. */
  static void process(Fusion.Process process) {
    Entry entry = CURRENT.get();
    if (entry != null) {
      entry.processes.add(process);
    }
  }

  // JUnit

  @Override
  public void beforeEach(ExtensionContext context) {
    if (laneStarted == 0) {
      laneStarted = System.currentTimeMillis();
    }
    Entry entry = new Entry(context.getRequiredTestMethod().getName());
    CURRENT.set(entry);
    ENTRIES.put(entry.test, entry);
    // Registering on the root context makes close() the last thing this JVM does.
    context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put(Report.class, this);
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    finish(context, null);
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    finish(context, cause == null ? "failed" : String.valueOf(cause.getMessage()));
  }

  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    finish(context, "aborted: " + (cause == null ? "" : cause.getMessage()));
  }

  @Override
  public void testDisabled(ExtensionContext context, Optional<String> reason) {
    finish(context, null);
  }

  private void finish(ExtensionContext context, String failure) {
    Entry entry = ENTRIES.get(context.getRequiredTestMethod().getName());
    if (entry != null) {
      entry.took = System.currentTimeMillis() - entry.started;
      entry.failure = failure;
    }
    CURRENT.remove();
  }

  /** Printed when the engine shuts down, after every test in this JVM. */
  @Override
  public void close() {
    if (ENTRIES.isEmpty()) {
      return;
    }
    ENTRIES.values().forEach(Report::collect);
    System.out.println(render());
  }

  /**
   * Copies every log that covers one test into its own directory. Best effort: a run that cannot
   * collect its logs still reports its results, with the live paths in place of the copies.
   */
  private static void collect(Entry row) {
    Path into = COLLECTED.resolve(row.test);
    try {
      if (row.olakeLogs != null && Files.isDirectory(row.olakeLogs)) {
        copyInto(row.olakeLogs, into.resolve("olake"));
      }
      for (Fusion.Process process : row.processes) {
        Path from = COMPACTION_LOGS.resolve(process.id());
        if (Files.isDirectory(from)) {
          copyInto(from, into.resolve("compaction").resolve(process.id()));
        }
      }
      if (Files.isDirectory(into)) {
        row.collected = into;
      }
    } catch (IOException e) {
      System.out.println("could not collect the logs of " + row.test + ": " + e);
    }
  }

  private static void copyInto(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    for (Path file : listFiles(from)) {
      Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // Rendering

  /** Renders the table, with no line left padded to the column width. */
  private static String render() {
    List<Entry> rows =
        ENTRIES.values().stream()
            .sorted((a, b) -> Long.compare(b.took, a.took))
            .collect(Collectors.toList());
    List<Entry> failed = rows.stream().filter(r -> r.failure != null).collect(Collectors.toList());

    int testWidth = width(rows, r -> r.test, "TEST");
    int tableWidth = width(rows, r -> r.table, "TABLE");
    int processWidth = width(rows, Entry::processSummary, "OPTIMIZING PROCESSES");
    String format =
        "%-6s  %-" + testWidth + "s  %8s  %-" + tableWidth + "s  %-" + processWidth + "s";
    String rule = "-".repeat(6 + 2 + testWidth + 2 + 8 + 2 + tableWidth + 2 + processWidth);

    StringBuilder out = new StringBuilder("\n").append(rule).append('\n');
    out.append(
            String.format(
                " Fusion integration tests: %d run, %d passed, %d failed, %s wall time",
                rows.size(),
                rows.size() - failed.size(),
                failed.size(),
                time(System.currentTimeMillis() - laneStarted)))
        .append('\n')
        .append(rule)
        .append('\n')
        .append(String.format(format, "RESULT", "TEST", "TIME", "TABLE", "OPTIMIZING PROCESSES"))
        .append('\n');
    for (Entry row : rows) {
      out.append(
              String.format(
                  format,
                  row.failure == null ? "PASS" : "FAIL",
                  row.test,
                  time(row.took),
                  row.table.isEmpty() ? "-" : row.table,
                  row.processSummary()))
          .append('\n');
    }
    out.append(rule).append('\n');
    for (Entry row : failed) {
      out.append(failureDetail(row));
    }
    return out.toString()
        .lines()
        .map(line -> line.replaceAll("\\s+$", ""))
        .collect(Collectors.joining("\n", "", "\n"));
  }

  /** A failed test's message and every log that covers it, as paths a terminal can open. */
  private static String failureDetail(Entry row) {
    StringBuilder out = new StringBuilder("\nFAIL ").append(row.test).append('\n');
    if (row.failure != null) {
      out.append("  ").append(row.failure.replace("\n", "\n  ")).append('\n');
    }
    for (Fusion.Process process : row.processes) {
      if (process.failReason() != null && !process.failReason().isEmpty()) {
        out.append("  process ")
            .append(process.id())
            .append(" failed: ")
            .append(process.failReason())
            .append('\n');
      }
    }
    if (row.collected != null) {
      out.append("  logs (").append(row.collected.toAbsolutePath()).append("):\n");
      for (Path path : walk(row.collected)) {
        out.append("    ").append(row.collected.relativize(path)).append('\n');
      }
    } else {
      out.append("  logs:\n");
      for (Path path : logPaths(row)) {
        out.append("    ").append(path.toAbsolutePath()).append('\n');
      }
    }
    return out.toString();
  }

  private static List<Path> logPaths(Entry row) {
    List<Path> paths = new ArrayList<>();
    if (row.olakeLogs != null && Files.isDirectory(row.olakeLogs)) {
      paths.addAll(listFiles(row.olakeLogs));
    }
    // AMS writes one directory of logs per process, and the driver's log is the one that explains
    // a failed compaction: see LogWriter.
    for (Fusion.Process process : row.processes) {
      Path dir = COMPACTION_LOGS.resolve(process.id());
      paths.addAll(Files.isDirectory(dir) ? listFiles(dir) : List.of(dir));
    }
    if (paths.isEmpty()) {
      paths.add(COMPACTION_LOGS);
    }
    return paths;
  }

  /** Every file under a directory, deepest path last, for listing a collected folder. */
  private static List<Path> walk(Path dir) {
    try (var paths = Files.walk(dir)) {
      return paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      return List.of();
    }
  }

  private static List<Path> listFiles(Path dir) {
    try (var files = Files.list(dir)) {
      return files.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int width(
      List<Entry> rows, java.util.function.Function<Entry, String> of, String header) {
    return rows.stream().map(of).mapToInt(String::length).max().orElse(0) > header.length()
        ? rows.stream().map(of).mapToInt(String::length).max().orElse(0)
        : header.length();
  }

  private static String time(long millis) {
    long seconds = Math.round(millis / 1000.0);
    return seconds < 60 ? seconds + "s" : String.format("%dm %02ds", seconds / 60, seconds % 60);
  }
}
