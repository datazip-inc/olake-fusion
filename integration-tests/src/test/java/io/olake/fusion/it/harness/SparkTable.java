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

import io.olake.fusion.it.contract.Names;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One table, reached through the user's query engine (Spark SQL over Spark Connect). Writes are
 * what a user does in Spark; reads are rows and the engine's own metadata tables.
 */
public final class SparkTable {
  /** Iceberg's {@code content} values in the {@code files} metadata table. */
  public static final int DATA = 0;

  public static final int POSITION_DELETES = 1;
  public static final int EQUALITY_DELETES = 2;

  /**
   * OLake keeps a row deleted in the source as a tombstone with {@code _op_type = 'd'}; a user's
   * "current data" query leaves those out, and so does {@link #rows}.
   */
  private static final String LIVE = " WHERE _op_type IS DISTINCT FROM 'd'";

  /** One live file from the engine's {@code files} metadata table. */
  public static final class FileInfo {
    private final String path;
    private final int content;
    private final long size;

    FileInfo(String path, int content, long size) {
      this.path = path;
      this.content = content;
      this.size = size;
    }

    public String path() {
      return path;
    }

    public int content() {
      return content;
    }

    public long size() {
      return size;
    }

    @Override
    public String toString() {
      return "FileInfo[path=" + path + ", content=" + content + ", size=" + size + "]";
    }
  }

  private static SparkSession session;

  private final String namespace;
  private final String name;

  public SparkTable(String namespace, String name) {
    this.namespace = namespace;
    this.name = name;
  }

  public String namespace() {
    return namespace;
  }

  public String name() {
    return name;
  }

  public String ident() {
    return Names.CATALOG + ".`" + namespace + "`.`" + name + "`";
  }

  // Session

  public static synchronized SparkSession session() {
    if (session == null) {
      session = SparkSession.builder().remote(Env.SPARK_CONNECT_URL).getOrCreate();
    }
    return session;
  }

  public static List<Row> sql(String sql) {
    return session().sql(sql).collectAsList();
  }

  public static void exec(String sql) {
    sql(sql);
  }

  public static void recreateNamespace(String namespace) {
    exec("DROP NAMESPACE IF EXISTS " + Names.CATALOG + ".`" + namespace + "` CASCADE");
    exec("CREATE NAMESPACE " + Names.CATALOG + ".`" + namespace + "`");
  }

  /**
   * Creates a table the way a Spark user would, with the same business columns as the OLake table
   * plus {@code _olake_id} and {@code _op_type}, so every journey reads rows the same way.
   */
  public static SparkTable create(String namespace, String name, Map<String, String> properties) {
    SparkTable table = new SparkTable(namespace, name);
    StringBuilder props = new StringBuilder("'format-version' = '2'");
    properties.forEach(
        (k, v) -> props.append(", ").append(quote(k)).append(" = ").append(quote(v)));
    exec(
        "CREATE TABLE "
            + table.ident()
            + " (id BIGINT, region STRING, payload STRING, updated_at TIMESTAMP,"
            + " _olake_id STRING, _op_type STRING) USING iceberg"
            + " TBLPROPERTIES ("
            + props
            + ")");
    return table;
  }

  // Writes

  public void setProperties(Map<String, String> properties) {
    if (properties.isEmpty()) {
      return;
    }
    String pairs =
        properties.entrySet().stream()
            .map(e -> quote(e.getKey()) + " = " + quote(e.getValue()))
            .collect(Collectors.joining(", "));
    exec("ALTER TABLE " + ident() + " SET TBLPROPERTIES (" + pairs + ")");
  }

  /**
   * Appends {@code count} rows with ids {@code firstId..} as one file, all with {@code region}.
   * Columns the table has beyond the business columns get the values a user writer would give them:
   * {@code _olake_id} a unique key, the rest null.
   */
  public void append(String region, long firstId, int count) {
    List<String> select = new ArrayList<>();
    for (String column : columns()) {
      switch (column) {
        case "id":
          select.add("id");
          break;
        case "region":
          select.add(quote(region));
          break;
        case "payload":
          select.add("sha2(concat('spark-', CAST(id AS STRING)), 512)");
          break;
        case "updated_at":
          select.add("current_timestamp()");
          break;
        case "_olake_id":
          select.add("concat('spark-', CAST(id AS STRING))");
          break;
        case "_op_type":
          select.add("'c'");
          break;
        default:
          select.add("NULL");
      }
    }
    exec(
        "INSERT INTO "
            + ident()
            + " SELECT /*+ REPARTITION(1) */ "
            + String.join(", ", select)
            + " FROM range("
            + firstId
            + ", "
            + (firstId + count)
            + ")");
  }

  /** The payload {@link #append} writes for a row id, computed the same way in Java. */
  public static String payloadOf(long id) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-512")
              .digest(("spark-" + id).getBytes(StandardCharsets.UTF_8));
      return String.format("%0128x", new BigInteger(1, digest));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void deleteIds(Collection<Long> ids) {
    String list = ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    exec("DELETE FROM " + ident() + " WHERE id IN (" + list + ")");
  }

  public void drop() {
    exec("DROP TABLE IF EXISTS " + ident() + " PURGE");
  }

  // Reads

  public List<String> columns() {
    List<String> columns = new ArrayList<>();
    for (Row row : sql("DESCRIBE TABLE " + ident())) {
      String column = row.getString(0);
      if (column == null || column.isEmpty() || column.startsWith("#")) {
        break;
      }
      columns.add(column);
    }
    return columns;
  }

  /** Live rows (tombstones excluded) as sorted {@code id|region|payload} strings. */
  public List<String> rows() {
    List<String> rows = new ArrayList<>();
    for (Row row : sql("SELECT CAST(id AS STRING), region, payload FROM " + ident() + LIVE)) {
      rows.add(row.getString(0) + "|" + row.getString(1) + "|" + row.getString(2));
    }
    Collections.sort(rows);
    return rows;
  }

  /** Live data and delete files of the current snapshot. */
  public List<FileInfo> files() {
    List<FileInfo> files = new ArrayList<>();
    for (Row row :
        sql("SELECT file_path, content, file_size_in_bytes FROM " + ident() + ".files")) {
      files.add(new FileInfo(row.getString(0), row.getInt(1), row.getLong(2)));
    }
    return files;
  }

  /** Id of the current snapshot, or null when the table has none. */
  public String currentSnapshotId() {
    List<Row> rows =
        sql(
            "SELECT CAST(snapshot_id AS STRING) FROM "
                + ident()
                + ".snapshots ORDER BY committed_at DESC LIMIT 1");
    return rows.isEmpty() ? null : rows.get(0).getString(0);
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
