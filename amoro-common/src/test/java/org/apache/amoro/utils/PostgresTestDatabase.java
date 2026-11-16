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

package org.apache.amoro.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Creates throwaway databases on the PostgreSQL server used by tests, by default the one that
 * {@code make start-deps} starts. Each database is dropped when the JVM exits.
 */
public final class PostgresTestDatabase {
  public static final String DRIVER = "org.postgresql.Driver";
  public static final String USER = env("AMORO_TEST_POSTGRES_USER", "iceberg");
  public static final String PASSWORD = env("AMORO_TEST_POSTGRES_PASSWORD", "password");

  private static final String HOST_PORT = env("AMORO_TEST_POSTGRES_HOST_PORT", "localhost:5432");
  private static final String ADMIN_DATABASE = "postgres";

  private PostgresTestDatabase() {}

  /** Creates a database whose name starts with the given prefix and returns its JDBC URL. */
  public static String create(String prefix) {
    String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    execute("CREATE DATABASE " + name);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
                  } catch (RuntimeException e) {
                    System.err.println("Failed to drop test database " + name + ": " + e);
                  }
                }));
    return jdbcUrl(name);
  }

  private static String jdbcUrl(String database) {
    return "jdbc:postgresql://" + HOST_PORT + "/" + database;
  }

  private static void execute(String sql) {
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl(ADMIN_DATABASE), USER, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Cannot reach PostgreSQL at " + HOST_PORT + ". Start it with `make start-deps`.", e);
    }
  }

  private static String env(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null ? defaultValue : value;
  }
}
