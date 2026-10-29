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

package io.olake.fusion.server.terminal;

import io.olake.fusion.config.Configurations;
import io.olake.fusion.server.terminal.TerminalSessionFactory.SessionConfigOptions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

public class TestSparkContextUtil {

  private static final String CATALOG = "olake_iceberg";

  private static Configurations sessionConfig(String connector) {
    Configurations config = new Configurations();
    config.set(SessionConfigOptions.CATALOGS, Lists.newArrayList(CATALOG));
    config.set(SessionConfigOptions.CATALOG_URL_BASE, "thrift://localhost:1260");
    config.set(SessionConfigOptions.catalogConnector(CATALOG), connector);
    config.set(SessionConfigOptions.catalogProperty(CATALOG, "type"), "rest");
    config.set(SessionConfigOptions.catalogProperty(CATALOG, "uri"), "http://localhost:8181");
    return config;
  }

  @Test
  public void terminalLoadsOnlyTheIcebergExtension() {
    Map<String, String> sparkConf = SparkContextUtil.getSparkConf(sessionConfig("iceberg"));

    Assertions.assertEquals(
        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
        sparkConf.get("spark.sql.extensions"));
  }

  @Test
  public void icebergCatalogUsesIcebergSparkCatalog() {
    Map<String, String> sparkConf = SparkContextUtil.getSparkConf(sessionConfig("iceberg"));

    Assertions.assertEquals(
        "org.apache.iceberg.spark.SparkCatalog", sparkConf.get("spark.sql.catalog." + CATALOG));
    Assertions.assertEquals("rest", sparkConf.get("spark.sql.catalog." + CATALOG + ".type"));
    Assertions.assertEquals(
        "http://localhost:8181", sparkConf.get("spark.sql.catalog." + CATALOG + ".uri"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"mixed_iceberg", "mixed_hive", "unified", "paimon"})
  public void nonIcebergConnectorIsRejected(String connector) {
    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> SparkContextUtil.getSparkConf(sessionConfig(connector)));
    Assertions.assertTrue(
        e.getMessage().contains(connector),
        "message should name the connector, was: " + e.getMessage());
  }
}
