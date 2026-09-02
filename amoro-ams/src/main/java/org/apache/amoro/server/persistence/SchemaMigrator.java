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

package org.apache.amoro.server.persistence;

import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.AmoroManagementConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies pending schema migrations when AMS starts.
 *
 * <p>The init script only runs against an empty database, so anything added after a release has to
 * reach existing deployments some other way. Every migration lives in its own file under {@code
 * postgres/migration}; this class only decides which of them have not run yet.
 *
 * <h2>Adding a migration</h2>
 *
 * Drop a {@code V<n>__<name>.sql} file in that directory using the next free version number, and
 * add its file name to {@link #MIGRATIONS}. No schema DDL belongs in Java. The whole file is sent
 * to postgres as one statement, so {@code DO $$ ... $$} blocks and functions survive intact.
 *
 * <h2>Rules the scripts must follow</h2>
 *
 * <ul>
 *   <li>Write them so a database that already carries the change survives them - {@code CREATE
 *       TABLE IF NOT EXISTS}, {@code ADD COLUMN IF NOT EXISTS}, {@code CREATE INDEX IF NOT EXISTS}.
 *       A fresh database gets the upstream tables from the init script, which may already include
 *       something a later migration also adds.
 *   <li>Never edit a script that has shipped. Its checksum is recorded and AMS refuses to start
 *       when a recorded script has changed; add the next version instead.
 *   <li>There is no down migration. Undoing a change means writing a new version.
 *   <li>No {@code COMMIT} or {@code ROLLBACK} inside a script, and nothing that postgres forbids in
 *       a transaction such as {@code CREATE INDEX CONCURRENTLY} - AMS owns the transaction that
 *       wraps the script and the history row.
 * </ul>
 *
 * <h2>What is guaranteed</h2>
 *
 * Each script is applied in one transaction together with the row that records it. Postgres has
 * transactional DDL, so a failing migration leaves neither a half applied schema nor a history row,
 * and two AMS replicas starting at once cannot apply the same migration twice: the loser blocks on
 * the primary key, then finds the migration already recorded.
 */
public class SchemaMigrator {
  private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);

  private static final String MIGRATION_DIR = "postgres/migration/";
  private static final String HISTORY_SCRIPT = MIGRATION_DIR + "migration-history.sql";
  private static final String HISTORY_TABLE = "ams_schema_migration";

  /** Migration scripts in the order they must be applied. */
  private static final String[] MIGRATIONS = {"V1__platform_property.sql"};

  private SchemaMigrator() {}

  public static void migrate(DataSource ds, Configurations config) {
    if (!config.getBoolean(AmoroManagementConf.DB_AUTO_CREATE_TABLES)) {
      LOG.info("Skip schema migrations due to configuration");
      return;
    }
    String dbType = config.getString(AmoroManagementConf.DB_TYPE);
    if (!AmoroManagementConf.DB_TYPE_POSTGRES.equals(dbType)) {
      LOG.info("Skip schema migrations, they are only maintained for postgres, not {}", dbType);
      return;
    }

    Map<Integer, String> declared = declaredMigrations();

    Map<Integer, AppliedMigration> applied;
    try {
      applied = prepareHistory(ds);
    } catch (Exception e) {
      // A role that may not create tables manages its schema by hand; that is a supported setup and
      // must not stop the server from starting.
      LOG.warn(
          "Cannot read or create {}, skipping schema migrations. Apply them manually if the server "
              + "reports missing tables.",
          HISTORY_TABLE,
          e);
      return;
    }

    for (Map.Entry<Integer, String> entry : declared.entrySet()) {
      int version = entry.getKey();
      String script = entry.getValue();
      String checksum = checksumOf(MIGRATION_DIR + script);
      AppliedMigration previous = applied.get(version);
      if (previous == null) {
        apply(ds, version, script, checksum);
      } else {
        verifyUnchanged(version, script, checksum, previous);
      }
    }
  }

  /** Declared migrations by version, rejecting a version used twice. */
  private static Map<Integer, String> declaredMigrations() {
    Map<Integer, String> declared = new TreeMap<>();
    for (String script : MIGRATIONS) {
      int version = versionOf(script);
      String clash = declared.put(version, script);
      if (clash != null) {
        // Two branches picking the same number would otherwise leave one migration silently
        // unapplied, because the version is all the history table keys on.
        throw new IllegalStateException(
            "Duplicate migration version " + version + ": " + clash + " and " + script);
      }
    }
    return declared;
  }

  /**
   * Fails when a script that already ran is not the script on disk any more: the database no longer
   * matches what this build expects.
   */
  private static void verifyUnchanged(
      int version, String script, String checksum, AppliedMigration previous) {
    if (!script.equals(previous.scriptName)) {
      throw new IllegalStateException(
          String.format(
              "Migration version %d was applied as %s but is now declared as %s",
              version, previous.scriptName, script));
    }
    if (previous.checksum != null && !previous.checksum.equals(checksum)) {
      throw new IllegalStateException(
          String.format(
              "Migration %s changed after it was applied. Add a new version instead of editing it.",
              script));
    }
  }

  /** Creates the history table when missing and returns the migrations already applied. */
  private static Map<Integer, AppliedMigration> prepareHistory(DataSource ds) throws SQLException {
    try (Connection connection = ds.getConnection()) {
      try {
        execute(connection, readScript(HISTORY_SCRIPT));
        connection.commit();
      } catch (Exception e) {
        // Two instances running CREATE TABLE IF NOT EXISTS at the same moment make postgres raise a
        // unique violation on its own catalog. Reading the table settles who was right: if it is
        // there now the other instance created it, and if it is not the read fails and the caller
        // skips the migrations.
        connection.rollback();
        LOG.debug("Could not create {}, checking whether it exists already", HISTORY_TABLE, e);
      }

      Map<Integer, AppliedMigration> applied = new HashMap<>();
      try (Statement statement = connection.createStatement();
          ResultSet rs =
              statement.executeQuery(
                  "SELECT version, script_name, checksum FROM " + HISTORY_TABLE)) {
        while (rs.next()) {
          applied.put(rs.getInt(1), new AppliedMigration(rs.getString(2), rs.getString(3)));
        }
      }
      connection.rollback();
      return applied;
    }
  }

  private static void apply(DataSource ds, int version, String script, String checksum) {
    LOG.info("Applying schema migration {}", script);
    String sql = readScript(MIGRATION_DIR + script);
    try (Connection connection = ds.getConnection()) {
      try {
        try (PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO "
                    + HISTORY_TABLE
                    + "(version, script_name, checksum) VALUES(?, ?, ?)")) {
          insert.setInt(1, version);
          insert.setString(2, script);
          insert.setString(3, checksum);
          insert.executeUpdate();
        }
        execute(connection, sql);
        connection.commit();
        LOG.info("Applied schema migration {}", script);
      } catch (Exception e) {
        connection.rollback();
        // Reuse this connection rather than borrowing a second one: a pool sized at one would
        // otherwise stall here until it times out.
        if (isAlreadyApplied(connection, version)) {
          LOG.info("Schema migration {} was applied by another AMS instance", script);
          return;
        }
        throw e;
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to apply schema migration " + script, e);
    }
  }

  private static boolean isAlreadyApplied(Connection connection, int version) {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT 1 FROM " + HISTORY_TABLE + " WHERE version = ?")) {
      select.setInt(1, version);
      try (ResultSet rs = select.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * Sends a script as a single statement. Postgres runs every statement inside it, which keeps
   * dollar quoted blocks intact - splitting a script on semicolons would cut them in half.
   */
  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String readScript(String resource) {
    try (InputStream stream = SchemaMigrator.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Cannot find migration script: " + resource);
      }
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = stream.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read migration script: " + resource, e);
    }
  }

  private static String checksumOf(String resource) {
    // Line endings are normalized so a checkout on another platform is not mistaken for an edit.
    String normalized = readScript(resource).replace("\r\n", "\n");
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to checksum migration script: " + resource, e);
    }
  }

  private static final class AppliedMigration {
    private final String scriptName;
    private final String checksum;

    private AppliedMigration(String scriptName, String checksum) {
      this.scriptName = scriptName;
      this.checksum = checksum;
    }
  }

  /** {@code V12__something.sql} carries version 12. */
  private static int versionOf(String script) {
    int end = script.indexOf("__");
    if (!script.startsWith("V") || end < 2) {
      throw new IllegalStateException("Malformed migration script name: " + script);
    }
    return Integer.parseInt(script.substring(1, end));
  }
}
