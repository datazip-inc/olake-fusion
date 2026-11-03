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
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

public class SparkContextUtil {

  public static final String ICEBERG_EXTENSION =
      "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions";
  public static final String ICEBERG_CATALOG = "org.apache.iceberg.spark.SparkCatalog";
  public static final String ICEBERG_CONNECTOR = "iceberg";

  public static Map<String, String> getSparkConf(Configurations sessionConfig) {
    Map<String, String> sparkConf = Maps.newLinkedHashMap();
    sparkConf.put("spark.sql.extensions", ICEBERG_EXTENSION);

    List<String> catalogs = sessionConfig.get(TerminalSessionFactory.SessionConfigOptions.CATALOGS);
    for (String catalog : catalogs) {
      String connector =
          sessionConfig.get(TerminalSessionFactory.SessionConfigOptions.catalogConnector(catalog));
      Preconditions.checkArgument(
          ICEBERG_CONNECTOR.equalsIgnoreCase(connector),
          "Unsupported terminal connector %s for catalog %s, only %s is supported",
          connector,
          catalog,
          ICEBERG_CONNECTOR);
      String sparkCatalogPrefix = "spark.sql.catalog." + catalog;
      Map<String, String> properties =
          TerminalSessionFactory.SessionConfigOptions.getCatalogProperties(sessionConfig, catalog);
      for (String key : properties.keySet()) {
        sparkConf.put(sparkCatalogPrefix + "." + key, properties.get(key));
      }
      sparkConf.put(sparkCatalogPrefix, ICEBERG_CATALOG);
    }
    return sparkConf;
  }
}
