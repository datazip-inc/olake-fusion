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
import java.math.BigInteger;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Fusion as OLake-UI's backend sees it: the REST API, authenticated the way that backend does it,
 * and the product verbs built on it. The admin logs in once to create an API token; every other
 * request carries {@code apiKey} and {@code signature} query parameters and no session cookie.
 */
public final class Fusion {
  /** An optimizer counts as alive when Fusion saw its heartbeat within this window. */
  private static final Duration HEARTBEAT_FRESHNESS = Duration.ofSeconds(60);

  private static final int PAGE_SIZE = 1000;

  /** OLake-UI's limit for a table settings change. */
  private static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(5);

  private static final Set<String> TERMINAL_ACTIVE = Set.of("Created", "Running");
  private static final String TERMINAL_FINISHED = "Finished";
  /** Log line the terminal writes when a statement throws. */
  private static final String TERMINAL_STATEMENT_FAILED = "meet exception during execution.";

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

  private final String baseUrl;
  /** The login session: only for logging in, the current user and creating the API token. */
  private final HttpClient web =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .connectTimeout(Duration.ofSeconds(10))
          .build();
  /**
   * Token-signed requests, without cookies. Fusion keys a terminal session by the session cookie,
   * so without one every terminal call gets its own session, as OLake-UI's do.
   */
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private volatile String apiKey;
  private volatile String secret;

  public Fusion(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  // Session

  /**
   * Logs in as the admin user and, when that succeeds, creates the API token every later request is
   * signed with (OLake-UI's {@code generateToken}). Returns the login envelope code.
   */
  public synchronized int login() {
    JsonNode envelope =
        send(
            web,
            "POST",
            ApiPaths.LOGIN,
            Map.of(Fields.USER, Names.ADMIN_USER, Fields.PASSWORD, Names.ADMIN_PASSWORD));
    int code = (int) Json.number(envelope, Fields.CODE);
    if (code == 200) {
      JsonNode token =
          result(
              "POST",
              ApiPaths.API_TOKEN_CREATE,
              send(web, "POST", ApiPaths.API_TOKEN_CREATE, null));
      apiKey = Json.text(token, Fields.API_KEY);
      secret = Json.text(token, Fields.SECRET);
    }
    return code;
  }

  public JsonNode currentUser() {
    return result("GET", ApiPaths.CURRENT_USER, send(web, "GET", ApiPaths.CURRENT_USER, null));
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

  /**
   * Sets table properties the way OLake-UI's table settings do: one {@code ALTER TABLE ... SET
   * TBLPROPERTIES} through Fusion's terminal, then the session log is polled until it ends. The
   * terminal reports {@code Finished} even when a statement failed, so its log is checked too.
   */
  public void setTableProperties(String database, String table, Map<String, String> properties) {
    String pairs =
        new TreeMap<>(properties)
            .entrySet().stream()
                .map(e -> quote(e.getKey()) + "=" + quote(e.getValue()))
                .collect(Collectors.joining(", "));
    String sql = "ALTER TABLE " + database + "." + table + " SET TBLPROPERTIES (" + pairs + ");";
    JsonNode session =
        call(
            "POST",
            String.format(ApiPaths.TERMINAL_EXECUTE, encode(Names.CATALOG)),
            Map.of(Fields.SQL, sql));
    String logsPath =
        String.format(ApiPaths.TERMINAL_LOGS, encode(Json.text(session, Fields.SESSION_ID)));
    JsonNode log =
        Await.until(
            "terminal session of: " + sql,
            TERMINAL_TIMEOUT,
            () -> get(logsPath),
            l -> !TERMINAL_ACTIVE.contains(Json.text(l, Fields.LOG_STATUS)));
    List<String> lines =
        Json.array(log, Fields.LOGS).stream().map(JsonNode::asText).collect(Collectors.toList());
    if (!TERMINAL_FINISHED.equals(Json.text(log, Fields.LOG_STATUS))
        || lines.stream().anyMatch(line -> line.contains(TERMINAL_STATEMENT_FAILED))) {
      throw new IllegalStateException("terminal failed: " + sql + "\n" + String.join("\n", lines));
    }
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

  /**
   * Waits for the first new process that matches and returns it. Each poll reads the table's
   * optimizing-process list from Fusion's REST API ({@code GET
   * .../tables/{table}/optimizing-processes}), the list OLake-UI's run history shows.
   */
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

  // Snapshots tab

  /** Id of the newest snapshot in the snapshots tab, or null when the table has none. */
  public String latestSnapshotId(String database, String table) {
    return page(String.format(ApiPaths.SNAPSHOTS, (Object[]) ids(database, table))).stream()
        .max(Comparator.comparingLong(s -> Json.number(s, Fields.COMMIT_TIME)))
        .map(s -> Json.text(s, Fields.SNAPSHOT_ID))
        .orElse(null);
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
    if (apiKey == null) {
      login();
    }
    JsonNode envelope = send(http, method, signed(path), body);
    if (Json.number(envelope, Fields.CODE) == 403) {
      // The token is unknown (Fusion's database was reset): create a new one once.
      login();
      envelope = send(http, method, signed(path), body);
    }
    return result(method, path, envelope);
  }

  private static JsonNode result(String method, String path, JsonNode envelope) {
    if (Json.number(envelope, Fields.CODE) != 200) {
      throw new IllegalStateException(method + " " + path + " failed: " + envelope);
    }
    return Json.required(envelope, Fields.RESULT);
  }

  /**
   * Appends {@code apiKey} and {@code signature}: the MD5 of apiKey + the query parameters (sorted
   * by name, each as name + decoded value, blank values left out) + secret. Without parameters the
   * UTC date ({@code yyyyMMdd}) stands in for them. Same calculation as OLake-UI's client.
   */
  private String signed(String path) {
    int q = path.indexOf('?');
    Map<String, String> params = new TreeMap<>();
    if (q >= 0) {
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        String value =
            eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        if (!value.isBlank()) {
          params.putIfAbsent(
              URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
              value);
        }
      }
    }
    String encrypt =
        params.isEmpty()
            ? LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
            : params.entrySet().stream()
                .map(e -> e.getKey() + e.getValue())
                .collect(Collectors.joining());
    return path
        + (q >= 0 ? "&" : "?")
        + ApiPaths.API_KEY_PARAM
        + "="
        + encode(apiKey)
        + "&"
        + ApiPaths.SIGNATURE_PARAM
        + "="
        + md5(apiKey + encrypt + secret);
  }

  private static String md5(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
      return String.format("%032x", new BigInteger(1, digest));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code web} requests carry the UI's source header and the session cookie. */
  private JsonNode send(HttpClient client, String method, String path, Object body) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(baseUrl + ApiPaths.BASE + path))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json");
      if (client == web) {
        request.header(ApiPaths.SOURCE_HEADER, ApiPaths.SOURCE_HEADER_VALUE);
      }
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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

  private static String quote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
