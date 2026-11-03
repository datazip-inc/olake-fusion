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

package org.apache.amoro.server.dashboard;

import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.catalog.CatalogManager;
import org.apache.amoro.server.dashboard.controller.TableController;
import org.apache.amoro.server.persistence.SnapshotExpiryTestBase;
import org.apache.amoro.server.table.TableManager;
import org.apache.amoro.utils.JacksonUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** {@code GET .../snapshot-expiry-processes} served by {@link TableController} over HTTP. */
public class TestSnapshotExpiryProcessesEndpoint extends SnapshotExpiryTestBase {

  private static final String PATH =
      "/api/ams/v1/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/snapshot-expiry-processes";

  private Javalin app;
  private volatile Exception handlerFailure;

  @Before
  public void startServer() {
    TableController controller =
        new TableController(
            mock(CatalogManager.class),
            mock(TableManager.class),
            mock(ServerTableDescriptor.class),
            new Configurations());
    app =
        Javalin.create(config -> config.jsonMapper(JavalinJsonMapper.createDefaultJsonMapper()))
            .get(PATH, controller::getSnapshotExpiryProcesses)
            .exception(
                Exception.class,
                (e, ctx) -> {
                  handlerFailure = e;
                  ctx.status(500);
                })
            .start(0);
  }

  @After
  public void stopServer() {
    app.stop();
  }

  @Test
  public void returnsRunsWithTheirFields() throws Exception {
    long tableId = insertTable("catalog", "db", "orders");
    long now = System.currentTimeMillis();
    insertRun(
        1001L,
        tableId,
        "SUCCESS",
        0,
        now - 3000,
        null,
        "{\"snapshotsBefore\":\"10\",\"snapshotsRetained\":\"7\",\"snapshotsExpired\":\"3\"}");
    insertRun(1002L, tableId, "FAILED", 2, now - 2000, "conflict 3", "{}");
    insertRun(1003L, tableId, "RUNNING", 0, now - 1000, null, null);

    Map<String, Object> page = get("catalog", "db", "orders", "page=1&pageSize=2");

    Assert.assertEquals(3, ((Number) page.get("total")).intValue());
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("list");
    Assert.assertEquals(2, items.size());
    Assert.assertEquals("1003", String.valueOf(items.get(0).get("processId")));
    Assert.assertEquals("1002", String.valueOf(items.get(1).get("processId")));
    for (String field :
        new String[] {
          "processId", "status", "startTime", "finishTime", "duration", "failReason", "summary"
        }) {
      Assert.assertTrue("missing field " + field, items.get(1).containsKey(field));
    }
    Assert.assertEquals("FAILED", items.get(1).get("status"));
    Assert.assertEquals("conflict 3", items.get(1).get("failReason"));

    Map<String, Object> failedOnly = get("catalog", "db", "orders", "status=FAILED");
    List<Map<String, Object>> failed = (List<Map<String, Object>>) failedOnly.get("list");
    Assert.assertEquals(1, failed.size());
    Assert.assertEquals("1002", String.valueOf(failed.get(0).get("processId")));
  }

  private static long insertTable(String catalog, String db, String table) throws Exception {
    execute(
        "INSERT INTO table_identifier (catalog_name, db_name, table_name, format)"
            + " VALUES (?, ?, ?, 'ICEBERG')",
        catalog,
        db,
        table);
    return ((Number)
            query(
                    "SELECT table_id FROM table_identifier WHERE catalog_name = ? AND db_name = ?"
                        + " AND table_name = ?",
                    catalog,
                    db,
                    table)
                .get(0)
                .get("table_id"))
        .longValue();
  }

  /** The {@code result} of the response envelope. */
  private Map<String, Object> get(String catalog, String db, String table, String query)
      throws Exception {
    String url =
        String.format(
            "http://localhost:%d/api/ams/v1/tables/catalogs/%s/dbs/%s/tables/%s"
                + "/snapshot-expiry-processes?%s",
            app.port(), catalog, db, table, query);
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    if (handlerFailure != null) {
      throw handlerFailure;
    }
    Assert.assertEquals(response.body(), 200, response.statusCode());
    Map<String, Object> body = JacksonUtil.parseObject(response.body(), Map.class);
    return (Map<String, Object>) body.get("result");
  }
}
