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

package io.olake.fusion.server.persistence;

import io.olake.fusion.config.Configurations;
import io.olake.fusion.server.FusionManagementConf;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.ibatis.session.SqlSession;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test persistence on PostgreSQL in a Docker container (Testcontainers).
 *
 * <p>One container is started per JVM, the first time this class is loaded, and reused by every
 * test class. The schema is created by the production code path: {@link DataSourceFactory} runs the
 * init script and every {@link SchemaMigrator} migration, so tests exercise the real Postgres DDL.
 * The MyBatis session factory is a JVM singleton bound to the first data source, so all test
 * classes share one database; as a {@code @ClassRule} or {@code @Rule}, this resource truncates
 * every table except the migration history after the class or test, the same isolation the former
 * Derby harness gave. The container is removed when the JVM exits.
 */
public class PostgresPersistence extends ExternalResource {
  private static final Logger LOG = LoggerFactory.getLogger(PostgresPersistence.class);

  /** Same major version as the local-test and OLake-UI deployments. */
  private static final String IMAGE = "postgres:15";

  private static final String DATABASE = "fusion_test";
  private static final String USER = "fusion";
  private static final String PASSWORD = "fusion";
  private static final int PORT = 5432;
  private static final String HISTORY_TABLE = "ams_schema_migration";

  private static final GenericContainer<?> CONTAINER;

  static {
    try {
      CONTAINER =
          new GenericContainer<>(DockerImageName.parse(IMAGE))
              .withEnv("POSTGRES_DB", DATABASE)
              .withEnv("POSTGRES_USER", USER)
              .withEnv("POSTGRES_PASSWORD", PASSWORD)
              // Durability is irrelevant for a throwaway test database.
              .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200")
              .withExposedPorts(PORT)
              // The server logs this once for the init run and once for the real start.
              .waitingFor(
                  Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                      .withStartupTimeout(Duration.ofMinutes(2)));
      CONTAINER.start();
      DataSource ds = DataSourceFactory.createDataSource(configurations());
      SqlSessionFactoryProvider.getInstance().init(ds);
      LOG.info("Initialized postgres persistence with url: {}", jdbcUrl());
      truncateAllTables();
    } catch (Exception e) {
      throw new RuntimeException("Failed to start the test postgres", e);
    }
  }

  /** Database configuration pointing at the test container. */
  public static Configurations configurations() {
    Configurations configurations = new Configurations();
    configurations.set(FusionManagementConf.DB_TYPE, FusionManagementConf.DB_TYPE_POSTGRES);
    configurations.set(FusionManagementConf.DB_DRIVER_CLASS_NAME, "org.postgresql.Driver");
    configurations.set(FusionManagementConf.DB_CONNECTION_URL, jdbcUrl());
    configurations.set(FusionManagementConf.DB_USER_NAME, USER);
    configurations.set(FusionManagementConf.DB_PASSWORD, PASSWORD);
    return configurations;
  }

  public static String jdbcUrl() {
    return String.format(
        "jdbc:postgresql://%s:%d/%s", CONTAINER.getHost(), CONTAINER.getMappedPort(PORT), DATABASE);
  }

  public static String username() {
    return USER;
  }

  public static String password() {
    return PASSWORD;
  }

  @Override
  protected void after() {
    truncateAllTables();
  }

  /** Script names of every migration in {@code SchemaMigrator.MIGRATIONS}, in order. */
  public List<String> expectedMigrations() {
    try {
      Field field = SchemaMigrator.class.getDeclaredField("MIGRATIONS");
      field.setAccessible(true);
      return Arrays.asList((String[]) field.get(null));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot read SchemaMigrator.MIGRATIONS", e);
    }
  }

  /** Script names recorded as applied in the test database, by version. */
  public List<String> appliedMigrations() {
    List<String> applied = Lists.newArrayList();
    try (SqlSession session = SqlSessionFactoryProvider.getInstance().get().openSession(true);
        Statement statement = session.getConnection().createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT script_name FROM " + HISTORY_TABLE + " ORDER BY version")) {
      while (rs.next()) {
        applied.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read " + HISTORY_TABLE, e);
    }
    return Collections.unmodifiableList(applied);
  }

  private static void truncateAllTables() {
    try (SqlSession sqlSession = SqlSessionFactoryProvider.getInstance().get().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement()) {
      List<String> tables = Lists.newArrayList();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT table_name FROM information_schema.tables"
                  + " WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'"
                  + " AND table_name <> '"
                  + HISTORY_TABLE
                  + "'")) {
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
      }
      if (!tables.isEmpty()) {
        // One statement, so foreign keys between the truncated tables never get in the way.
        statement.execute("TRUNCATE TABLE " + String.join(", ", tables));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Clear table failed", e);
    }
  }
}
