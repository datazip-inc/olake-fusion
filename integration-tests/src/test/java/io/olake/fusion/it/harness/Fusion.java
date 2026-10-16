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

package io.olake.fusion.it.harness;

import com.fasterxml.jackson.databind.JsonNode;
import io.olake.fusion.it.contract.ApiPaths;
import io.olake.fusion.it.contract.Fields;
import io.olake.fusion.it.contract.Names;
import io.olake.fusion.it.contract.ProcessStatus;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Fusion as the UI sees it: the REST session and the product verbs built on it. */
public final class Fusion {
  /** An optimizer counts as alive when Fusion saw its heartbeat within this window. */
  private static final Duration HEARTBEAT_FRESHNESS = Duration.ofSeconds(60);

  private static final int PAGE_SIZE = 1000;

  /** One optimizing process, as listed by the table's optimizing tab. */
  public static final class Process {
    private final String id;
    private final String type;
    private final String status;
    private final long startTime;
    private final long finishTime;
    private final long totalTasks;
    private final String failReason;

    Process(
        String id,
        String type,
        String status,
        long startTime,
        long finishTime,
        long totalTasks,
        String failReason) {
      this.id = id;
      this.type = type;
      this.status = status;
      this.startTime = startTime;
      this.finishTime = finishTime;
      this.totalTasks = totalTasks;
      this.failReason = failReason;
    }

    public String id() {
      return id;
    }

    public String type() {
      return type;
    }

    public String status() {
      return status;
    }

    public long startTime() {
      return startTime;
    }

    public long finishTime() {
      return finishTime;
    }

    public long totalTasks() {
      return totalTasks;
    }

    public String failReason() {
      return failReason;
    }

    public boolean ran() {
      return !ProcessStatus.SKIPPED.equals(status);
    }

    public boolean terminal() {
      return ProcessStatus.TERMINAL.contains(status);
    }

    @Override
    public String toString() {
      return "Process[id="
          + id
          + ", type="
          + type
          + ", status="
          + status
          + ", startTime="
          + startTime
          + ", finishTime="
          + finishTime
          + ", totalTasks="
          + totalTasks
          + ", failReason="
          + failReason
          + "]";
    }
  }

  /** One live file, as listed by the table's files tab. */
  public static final class FileEntry {
    private final String path;
    private final String partition;
    private final String fileType;
    private final long size;

    FileEntry(String path, String partition, String fileType, long size) {
      this.path = path;
      this.partition = partition;
      this.fileType = fileType;
      this.size = size;
    }

    public String path() {
      return path;
    }

    public String partition() {
      return partition;
    }

    public String fileType() {
      return fileType;
    }

    public long size() {
      return size;
    }

    @Override
    public String toString() {
      return "FileEntry[path="
          + path
          + ", partition="
          + partition
          + ", fileType="
          + fileType
          + ", size="
          + size
          + "]";
    }
  }

  private final String baseUrl;
  private final HttpClient http =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .connectTimeout(Duration.ofSeconds(10))
          .build();
  private volatile boolean loggedIn;

  public Fusion(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  // Session

  /** Logs in as the admin user; returns the envelope code. */
  public synchronized int login() {
    JsonNode envelope =
        send(
            "POST",
            ApiPaths.LOGIN,
            Map.of(Fields.USER, Names.ADMIN_USER, Fields.PASSWORD, Names.ADMIN_PASSWORD));
    int code = (int) Json.number(envelope, Fields.CODE);
    loggedIn = code == 200;
    return code;
  }

  public JsonNode currentUser() {
    return get(ApiPaths.CURRENT_USER);
  }

  // Stack

  public Set<String> catalogs() {
    return Json.elements(get(ApiPaths.CATALOGS), ApiPaths.CATALOGS).stream()
        .map(c -> Json.text(c, Fields.CATALOG_NAME))
        .collect(Collectors.toSet());
  }

  public Set<String> resourceGroups() {
    return Json.elements(get(ApiPaths.RESOURCE_GROUPS), ApiPaths.RESOURCE_GROUPS).stream()
        .map(g -> Json.text(Json.required(g, Fields.RESOURCE_GROUP), Fields.NAME))
        .collect(Collectors.toSet());
  }

  /** Optimizers of the group whose heartbeat is fresh. */
  public List<JsonNode> liveOptimizers() {
    long now = System.currentTimeMillis();
    return page(String.format(ApiPaths.OPTIMIZERS, Names.OPTIMIZER_GROUP)).stream()
        .filter(o -> now - Json.number(o, Fields.TOUCH_TIME) < HEARTBEAT_FRESHNESS.toMillis())
        .collect(Collectors.toList());
  }

  // Tables

  public Set<String> tables(String database) {
    String path = String.format(ApiPaths.TABLES, Names.CATALOG, encode(database));
    return Json.elements(get(path), path).stream()
        .map(t -> Json.text(t, Fields.NAME))
        .collect(Collectors.toSet());
  }

  public void awaitTableListed(String database, String table) {
    Await.until(
        "Fusion lists " + database + "." + table,
        Duration.ofMinutes(3),
        () -> tables(database),
        tables -> tables.contains(table));
  }

  /** Health score from the table details (table summary). */
  public long healthScore(String database, String table) {
    JsonNode details = get(String.format(ApiPaths.TABLE_DETAILS, (Object[]) ids(database, table)));
    return Json.number(Json.required(details, Fields.TABLE_SUMMARY), Fields.HEALTH_SCORE);
  }

  /** Health score of the table on the overview page; null when the overview does not list it. */
  public Long overviewHealthScore(String database, String table) {
    String fullName = Names.CATALOG + "." + database + "." + table;
    String path = ApiPaths.OVERVIEW_TOP + "?order=asc&orderBy=healthScore&limit=100000";
    for (JsonNode item : Json.elements(get(path), ApiPaths.OVERVIEW_TOP)) {
      if (fullName.equals(Json.text(item, Fields.TABLE_NAME))) {
        return Json.number(item, Fields.HEALTH_SCORE);
      }
    }
    return null;
  }

  // Optimizing processes

  public List<Process> processes(String database, String table) {
    return page(String.format(ApiPaths.PROCESSES, (Object[]) ids(database, table))).stream()
        .map(
            p ->
                new Process(
                    Json.text(p, Fields.PROCESS_ID),
                    Json.nullableText(p, Fields.OPTIMIZING_TYPE),
                    Json.text(p, Fields.STATUS),
                    Json.number(p, Fields.START_TIME),
                    Json.number(p, Fields.FINISH_TIME),
                    Json.number(p, Fields.TOTAL_TASKS),
                    Json.nullableText(p, Fields.FAIL_REASON)))
        .collect(Collectors.toList());
  }

  public Set<String> processIds(String database, String table) {
    return processes(database, table).stream().map(Process::id).collect(Collectors.toSet());
  }

  /** Processes not in {@code known}, oldest first. */
  public List<Process> newProcesses(String database, String table, Set<String> known) {
    return processes(database, table).stream()
        .filter(p -> !known.contains(p.id()))
        .sorted(Comparator.comparingLong(Process::startTime))
        .collect(Collectors.toList());
  }

  /** New processes that finished having done work (terminal, not SKIPPED). */
  public List<Process> newRuns(String database, String table, Set<String> known) {
    return newProcesses(database, table, known).stream()
        .filter(p -> p.ran() && p.terminal())
        .collect(Collectors.toList());
  }

  /** Waits for the first new process that matches and returns it. */
  public Process awaitProcess(
      String database,
      String table,
      Set<String> known,
      Duration timeout,
      Predicate<Process> match) {
    return Await.until(
            "a matching optimizing process on " + database + "." + table,
            timeout,
            () -> newProcesses(database, table, known).stream().filter(match).findFirst(),
            java.util.Optional::isPresent)
        .get();
  }

  /** Waits for the first new process that did work and finished. */
  public Process awaitFinishedRun(
      String database, String table, Set<String> known, Duration timeout) {
    return awaitProcess(database, table, known, timeout, p -> p.ran() && p.terminal());
  }

  // Snapshots and files tabs

  /** Id of the newest snapshot in the snapshots tab, or null when the table has none. */
  public String latestSnapshotId(String database, String table) {
    return page(String.format(ApiPaths.SNAPSHOTS, (Object[]) ids(database, table))).stream()
        .max(Comparator.comparingLong(s -> Json.number(s, Fields.COMMIT_TIME)))
        .map(s -> Json.text(s, Fields.SNAPSHOT_ID))
        .orElse(null);
  }

  /** Every live file of the table, from the partitions and files tabs. */
  public List<FileEntry> files(String database, String table, boolean partitioned) {
    String[] id = ids(database, table);
    List<String> partitions = new ArrayList<>();
    if (partitioned) {
      for (JsonNode p : page(String.format(ApiPaths.PARTITIONS, (Object[]) id))) {
        partitions.add(Json.text(p, Fields.PARTITION));
      }
    } else {
      partitions.add(ApiPaths.UNPARTITIONED);
    }
    List<FileEntry> files = new ArrayList<>();
    for (String partition : partitions) {
      String path = String.format(ApiPaths.PARTITION_FILES, id[0], id[1], id[2], encode(partition));
      for (JsonNode f : page(path)) {
        files.add(
            new FileEntry(
                Json.text(f, Fields.PATH),
                Json.text(f, Fields.PARTITION),
                Json.text(f, Fields.FILE_TYPE),
                Json.number(f, Fields.FILE_SIZE)));
      }
    }
    return files;
  }

  // HTTP

  private List<JsonNode> page(String path) {
    String sep = path.contains("?") ? "&" : "?";
    JsonNode result = get(path + sep + "page=1&pageSize=" + PAGE_SIZE);
    return Json.array(result, Fields.LIST);
  }

  public JsonNode get(String path) {
    return call("GET", path, null);
  }

  private JsonNode call(String method, String path, Object body) {
    JsonNode envelope = send(method, path, body);
    if (Json.number(envelope, Fields.CODE) == 403) {
      // The session expired or AMS restarted: log in again once.
      login();
      envelope = send(method, path, body);
    }
    if (Json.number(envelope, Fields.CODE) != 200) {
      throw new IllegalStateException(method + " " + path + " failed: " + envelope);
    }
    return Json.required(envelope, Fields.RESULT);
  }

  private JsonNode send(String method, String path, Object body) {
    if (!loggedIn && !ApiPaths.LOGIN.equals(path)) {
      login();
    }
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(baseUrl + ApiPaths.BASE + path))
              .timeout(Duration.ofSeconds(60))
              .header(ApiPaths.SOURCE_HEADER, ApiPaths.SOURCE_HEADER_VALUE)
              .header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.body().isEmpty()) {
        throw new IllegalStateException(
            method + " " + path + " returned HTTP " + response.statusCode() + " with no body");
      }
      return Json.MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(method + " " + path + " interrupted", e);
    }
  }

  private static String[] ids(String database, String table) {
    return new String[] {encode(Names.CATALOG), encode(database), encode(table)};
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
