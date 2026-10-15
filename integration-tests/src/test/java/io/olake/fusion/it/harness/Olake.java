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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The user's ingest path: a source Postgres database and OLake syncing it into the catalog with
 * CDC. Every journey owns one source database, replication slot, publication and working dir.
 */
public final class Olake {
  public static final String TABLE = "orders";
  public static final String[] REGIONS = {"ap", "eu", "us"};

  private static final Path FIXTURES = Paths.get("fixtures").toAbsolutePath();
  private static final String SOURCE_USER = "source";
  private static final String SOURCE_PASSWORD = "password";
  private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(10);

  /** The stream selection the test writes from OLake's discovered streams.json. */
  private static final String SELECTED_STREAMS = "streams.selected.json";

  private final String database;
  private final String slot;
  private final String publication;
  private final boolean partitioned;
  private final Path workDir;

  public Olake(String journey, boolean partitioned, Path workDir) {
    this.database = ("src_" + journey + "_" + Env.RUN_ID).toLowerCase();
    this.slot = database + "_slot";
    this.publication = database + "_pub";
    this.partitioned = partitioned;
    this.workDir = workDir.resolve("olake").toAbsolutePath();
  }

  public static String regionOf(long id) {
    return REGIONS[(int) (id % REGIONS.length)];
  }

  // Source database

  /** Drops and recreates the source database, its table, publication and replication slot. */
  public void createSource() {
    execute(
        "source",
        "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots"
            + " WHERE slot_name = '"
            + slot
            + "' AND NOT active",
        "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)",
        "CREATE DATABASE " + database);
    execute(
        database,
        read(FIXTURES.resolve("source/schema.sql")),
        "CREATE PUBLICATION " + publication + " FOR TABLE " + TABLE,
        "SELECT pg_create_logical_replication_slot('" + slot + "', 'pgoutput')");
  }

  /** Inserts ids {@code firstId..firstId+count-1}; the region follows the id. */
  public void insert(long firstId, int count) {
    execute(
        database,
        "INSERT INTO "
            + TABLE
            + " (id, region, payload) SELECT g, (ARRAY['ap','eu','us'])[(g % 3) + 1],"
            + " md5(random()::text) || md5(random()::text) FROM generate_series("
            + firstId
            + ", "
            + (firstId + count - 1)
            + ") g");
  }

  /** Inserts exactly these ids; the region follows the id. */
  public void insert(Collection<Long> ids) {
    execute(
        database,
        "INSERT INTO "
            + TABLE
            + " (id, region, payload) SELECT g, (ARRAY['ap','eu','us'])[(g % 3) + 1],"
            + " md5(random()::text) || md5(random()::text) FROM unnest(ARRAY["
            + join(ids)
            + "]::bigint[]) g");
  }

  public void update(Collection<Long> ids) {
    execute(
        database,
        "UPDATE "
            + TABLE
            + " SET payload = md5(random()::text) || md5(random()::text), updated_at = now()"
            + " WHERE id IN ("
            + join(ids)
            + ")");
  }

  public void delete(Collection<Long> ids) {
    execute(database, "DELETE FROM " + TABLE + " WHERE id IN (" + join(ids) + ")");
  }

  /** Source rows as sorted {@code id|region|payload} strings: the oracle for OLake's rows. */
  public List<String> rows() {
    List<String> rows = new ArrayList<>();
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT id, region, payload FROM " + TABLE)) {
      while (rs.next()) {
        rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot read source rows of " + database, e);
    }
    Collections.sort(rows);
    return rows;
  }

  // OLake

  /** Renders the configs, runs discover and selects only {@link #TABLE} with CDC. */
  public void discover() {
    try {
      Files.createDirectories(workDir);
      Map<String, String> values =
          Map.of("${SOURCE_DB}", database, "${SLOT}", slot, "${PUBLICATION}", publication);
      render("olake/source.template.json", "source.json", values);
      render("olake/destination.template.json", "destination.json", values);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    olake("discover", "--config", "/mnt/config/source.json");
    patchStreams();
  }

  /** One OLake sync; the state file carries the CDC position between syncs. */
  public void sync() {
    List<String> args =
        new ArrayList<>(
            List.of(
                "sync",
                "--config",
                "/mnt/config/source.json",
                "--catalog",
                "/mnt/config/" + SELECTED_STREAMS,
                "--destination",
                "/mnt/config/destination.json"));
    if (Files.exists(workDir.resolve("state.json"))) {
      args.addAll(List.of("--state", "/mnt/config/state.json"));
    }
    olake(args.toArray(new String[0]));
  }

  /** The Iceberg namespace OLake writes to: its {@code destination_database}, ':' -> '_'. */
  public String namespace() {
    JsonNode stream = streamOf(readJson(workDir.resolve("streams.json")));
    return Json.text(stream, "destination_database").replace(':', '_');
  }

  public void dropSource() {
    execute(
        "source",
        "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)",
        "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots"
            + " WHERE slot_name = '"
            + slot
            + "' AND NOT active");
  }

  private void olake(String... args) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "run",
                "--rm",
                "--network",
                Env.DOCKER_NETWORK,
                "-v",
                workDir + ":/mnt/config",
                Env.OLAKE_IMAGE));
    command.addAll(List.of(args));
    String output = Docker.run(SYNC_TIMEOUT, command.toArray(new String[0]));
    try {
      Files.writeString(
          workDir.resolve(args[0] + "-" + System.currentTimeMillis() + ".log"), output);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Writes {@link #SELECTED_STREAMS}: OLake's discovered catalog with only {@link #TABLE} selected
   * and the settings from stream-settings.json.
   */
  private void patchStreams() {
    Path path = workDir.resolve("streams.json");
    // OLake runs as root in its container, so on Linux the streams.json it writes is not
    // writable by the test; the selection goes into a file the test owns.
    Path target = workDir.resolve(SELECTED_STREAMS);
    ObjectNode catalog = (ObjectNode) readJson(path);
    JsonNode settings = readJson(FIXTURES.resolve("olake/stream-settings.json"));

    ArrayNode selected = Json.MAPPER.createArrayNode();
    for (JsonNode entry : Json.array(Json.required(catalog, "selected_streams"), "public")) {
      if (TABLE.equals(Json.text(entry, "stream_name"))) {
        ObjectNode copy = ((ObjectNode) entry).deepCopy();
        copy.setAll((ObjectNode) Json.required(settings, "selected"));
        copy.put(
            "partition_regex",
            Json.text(
                Json.required(settings, "partition_regex"),
                partitioned ? "PARTITIONED" : "UNPARTITIONED"));
        selected.add(copy);
      }
    }
    if (selected.size() != 1) {
      throw new IllegalStateException("discover did not find public." + TABLE + ": " + catalog);
    }
    ((ObjectNode) catalog.get("selected_streams")).removeAll();
    ((ObjectNode) catalog.get("selected_streams")).set("public", selected);

    ArrayNode streams = Json.MAPPER.createArrayNode();
    for (JsonNode wrapper : Json.array(catalog, "streams")) {
      ObjectNode stream = (ObjectNode) Json.required(wrapper, "stream");
      if (TABLE.equals(Json.text(stream, "name"))) {
        stream.setAll((ObjectNode) Json.required(settings, "stream"));
        streams.add(wrapper);
      }
    }
    catalog.set("streams", streams);
    try {
      Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), catalog);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode streamOf(JsonNode catalog) {
    for (Iterator<JsonNode> it = Json.array(catalog, "streams").iterator(); it.hasNext(); ) {
      JsonNode stream = Json.required(it.next(), "stream");
      if (TABLE.equals(Json.text(stream, "name"))) {
        return stream;
      }
    }
    throw new IllegalStateException("streams.json has no stream " + TABLE);
  }

  private void render(String fixture, String target, Map<String, String> values)
      throws IOException {
    String content = read(FIXTURES.resolve(fixture));
    for (Map.Entry<String, String> value : values.entrySet()) {
      content = content.replace(value.getKey(), value.getValue());
    }
    Files.writeString(workDir.resolve(target), content);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode readJson(Path path) {
    try {
      return Json.MAPPER.readTree(path.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String join(Collection<Long> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
  }

  private static Connection connect(String db) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://" + Env.SOURCE_JDBC_HOST + "/" + db, SOURCE_USER, SOURCE_PASSWORD);
  }

  private static void execute(String db, String... statements) {
    try (Connection connection = connect(db);
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("source SQL on " + db + " failed: " + e.getMessage(), e);
    }
  }
}
