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
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-like persistence for AMS service tests: a real Postgres (the version OLake-UI's Docker
 * deployment runs) with the schema created the way the server creates it, the init script then
 * {@link SchemaMigrator}. Existing tests keep using Derby; the previous session factory is restored
 * after each class so they are not affected.
 */
public abstract class PostgresTestBase {

  private static final String IMAGE = "postgres:13";
  private static final String USER = "fusion";
  private static final String PASSWORD = "fusion";
  private static final String DATABASE = "fusion";

  static {
    // Testcontainers 1.17 asks Docker for API 1.32 by default, which Docker 29 rejects (minimum
    // 1.40). 1.44 is served by every Docker from 25 on, including the CI runners.
    if (System.getProperty("api.version") == null) {
      System.setProperty("api.version", "1.44");
    }
  }

  @ClassRule
  public static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(DockerImageName.parse(IMAGE))
          .withEnv("POSTGRES_USER", USER)
          .withEnv("POSTGRES_PASSWORD", PASSWORD)
          .withEnv("POSTGRES_DB", DATABASE)
          .withExposedPorts(5432)
          .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

  protected static Configurations config;
  protected static DataSource dataSource;
  private Object previousFactory;

  @BeforeClass
  public static void setUpPostgres() {
    config = new Configurations();
    config.set(
        AmoroManagementConf.DB_CONNECTION_URL,
        String.format(
            "jdbc:postgresql://%s:%d/%s",
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), DATABASE));
    config.set(AmoroManagementConf.DB_TYPE, AmoroManagementConf.DB_TYPE_POSTGRES);
    config.set(AmoroManagementConf.DB_DRIVER_CLASS_NAME, "org.postgresql.Driver");
    config.set(AmoroManagementConf.DB_USER_NAME, USER);
    config.set(AmoroManagementConf.DB_PASSWORD, PASSWORD);
    config.set(AmoroManagementConf.DB_AUTO_CREATE_TABLES, true);
    dataSource = DataSourceFactory.createDataSource(config);
  }

  /**
   * Points the shared session factory at this Postgres before every test, and puts back the one
   * other tests use after it. {@link SqlSessionFactoryProvider#init} keeps the first factory it
   * built, so the factory is swapped directly.
   */
  @Before
  public void usePostgres() throws Exception {
    discardLeakedSession();
    previousFactory = factoryField().get(SqlSessionFactoryProvider.getInstance());
    factoryField().set(SqlSessionFactoryProvider.getInstance(), null);
    SqlSessionFactoryProvider.getInstance().init(dataSource);
  }

  @After
  public void restorePreviousPersistence() throws Exception {
    clearTables();
    discardLeakedSession();
    factoryField().set(SqlSessionFactoryProvider.getInstance(), previousFactory);
  }

  private static Field factoryField() throws NoSuchFieldException {
    Field factory = SqlSessionFactoryProvider.class.getDeclaredField("sqlSessionFactory");
    factory.setAccessible(true);
    return factory;
  }

  /**
   * A test that failed while a session was open can leave it bound to this thread, and every later
   * session on the thread would then reuse that other database's connection.
   */
  private static void discardLeakedSession() throws ReflectiveOperationException {
    Field sessions = NestedSqlSession.class.getDeclaredField("sessions");
    sessions.setAccessible(true);
    ((ThreadLocal<?>) sessions.get(null)).remove();
  }

  private void clearTables() throws SQLException {
    for (String table :
        new String[] {"snapshot_expiry_process", "table_process", "table_identifier"}) {
      if (tableExists(table)) {
        execute("TRUNCATE TABLE " + table);
      }
    }
  }

  protected static boolean tableExists(String table) throws SQLException {
    return !query(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = ?",
            table)
        .isEmpty();
  }

  protected static void execute(String sql, Object... args) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = prepare(connection, sql, args)) {
      statement.execute();
      connection.commit();
    }
  }

  /** Rows as column-name to value maps, in result order. */
  protected static List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = prepare(connection, sql, args);
        ResultSet resultSet = statement.executeQuery()) {
      ResultSetMetaData meta = resultSet.getMetaData();
      List<Map<String, Object>> rows = new ArrayList<>();
      while (resultSet.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnLabel(i), resultSet.getObject(i));
        }
        rows.add(row);
      }
      return rows;
    }
  }

  private static PreparedStatement prepare(Connection connection, String sql, Object... args)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) {
      statement.setObject(i + 1, args[i]);
    }
    return statement;
  }
}
