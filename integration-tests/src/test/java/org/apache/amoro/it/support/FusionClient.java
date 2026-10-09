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

package org.apache.amoro.it.support;

import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the AMS dashboard REST API ({@code /api/ams/v1}). It logs in with the web session
 * flow (cookie + {@code X-Request-Source: Web}), which is what the UI uses.
 *
 * <p>Every call unwraps the {@code {code, message, result}} envelope and fails on a non-200 code.
 */
public class FusionClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String API = "/api/ams/v1";
  private static final FusionClient SHARED = new FusionClient(StackEnv.FUSION_URL);

  private final String baseUrl;
  private final HttpClient http;
  private volatile boolean loggedIn = false;

  public FusionClient(String baseUrl) {
    this.baseUrl = baseUrl;
    this.http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  public static FusionClient shared() {
    return SHARED;
  }

  // Session
  /** Logs in and returns the envelope code of the login call. */
  public synchronized int login() {
    JsonNode envelope =
        send(
            "POST",
            API + "/login",
            Map.of("user", StackEnv.FUSION_USER, "password", StackEnv.FUSION_PASSWORD));
    int code = envelope.path("code").asInt();
    loggedIn = code == 200;
    return code;
  }

  public JsonNode currentUser() {
    return get("/login/current");
  }

  // Optimizers
  public List<String> resourceGroupNames() {
    List<String> names = new ArrayList<>();
    for (JsonNode item : get("/optimize/resourceGroups")) {
      names.add(item.path("resourceGroup").path("name").asText());
    }
    return names;
  }

  public List<JsonNode> optimizers(String group) {
    return list(get("/optimize/optimizerGroups/" + group + "/optimizers?page=1&pageSize=100"));
  }

  public void scaleOutOptimizer(String group, int parallelism) {
    post("/optimize/optimizerGroups/" + group + "/optimizers", Map.of("parallelism", parallelism));
  }

  // Catalogs and tables
  public List<String> catalogNames() {
    List<String> names = new ArrayList<>();
    for (JsonNode item : get("/catalogs")) {
      names.add(item.path("catalogName").asText());
    }
    return names;
  }

  public List<String> databases(String catalog) {
    List<String> names = new ArrayList<>();
    for (JsonNode item : get("/catalogs/" + catalog + "/databases")) {
      names.add(item.asText());
    }
    return names;
  }

  public List<String> tables(String catalog, String db) {
    List<String> names = new ArrayList<>();
    for (JsonNode item : get("/catalogs/" + catalog + "/databases/" + db + "/tables")) {
      names.add(item.path("name").asText());
    }
    return names;
  }

  /** {@code ServerTableMeta}; the file summary and health score are under {@code tableSummary}. */
  public JsonNode tableDetails(String catalog, String db, String table) {
    return get(tablePath(catalog, db, table) + "/details");
  }

  public JsonNode tableSummary(String catalog, String db, String table) {
    return tableDetails(catalog, db, table).path("tableSummary");
  }

  /** Optimizing processes of a table, newest first, including SKIPPED records. */
  public List<JsonNode> processes(String catalog, String db, String table) {
    return list(get(tablePath(catalog, db, table) + "/optimizing-processes?page=1&pageSize=200"));
  }

  public List<JsonNode> processTasks(String catalog, String db, String table, String processId) {
    return list(
        get(
            tablePath(catalog, db, table)
                + "/optimizing-processes/"
                + processId
                + "/tasks?page=1&pageSize=200"));
  }

  /**
   * Optimizing status ({@code idle}, {@code pending}, {@code planning}, ...) of a table as the
   * optimizer group's table list reports it, or an empty string when the table is not listed.
   */
  public String optimizingStatus(String group, String table) {
    for (JsonNode item :
        list(
            get(
                "/optimize/optimizerGroups/"
                    + group
                    + "/tables?tableSearchInput="
                    + table
                    + "&page=1&pageSize=50"))) {
      if (item.path("tableName").asText().endsWith(table)) {
        return item.path("optimizeStatus").asText();
      }
    }
    return "";
  }

  /** All tables known to the overview cache, as {@code OverviewTopTableItem}. */
  public List<JsonNode> overviewTables() {
    List<JsonNode> items = new ArrayList<>();
    get("/overview/top?order=asc&orderBy=healthScore&limit=100000").forEach(items::add);
    return items;
  }

  // HTTP plumbing
  public JsonNode get(String path) {
    return call("GET", path, null);
  }

  public JsonNode post(String path, Object body) {
    return call("POST", path, body);
  }

  private JsonNode call(String method, String path, Object body) {
    JsonNode envelope = send(method, API + path, body);
    if (envelope.path("code").asInt() == 403) {
      // The web session expired or AMS restarted: log in again once.
      login();
      envelope = send(method, API + path, body);
    }
    if (envelope.path("code").asInt() != 200) {
      throw new IllegalStateException(method + " " + path + " failed: " + envelope);
    }
    return envelope.path("result");
  }

  private JsonNode send(String method, String path, Object body) {
    if (!path.endsWith("/login") && !loggedIn) {
      login();
    }
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(60))
              .header("X-Request-Source", "Web")
              .header("Content-Type", "application/json");
      if (body == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request.method(
            method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      }
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 500 && response.body().isEmpty()) {
        throw new IllegalStateException(
            method + " " + path + " returned HTTP " + response.statusCode());
      }
      return MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(method + " " + path + " interrupted", e);
    }
  }

  private static String tablePath(String catalog, String db, String table) {
    return "/tables/catalogs/"
        + encode(catalog)
        + "/dbs/"
        + encode(db)
        + "/tables/"
        + encode(table);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static List<JsonNode> list(JsonNode page) {
    JsonNode list = page.path("list");
    if (!list.isArray()) {
      return Collections.emptyList();
    }
    List<JsonNode> items = new ArrayList<>();
    list.forEach(items::add);
    return items;
  }
}
