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

package io.olake.fusion.server.catalog;

import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.catalog.CatalogTestHelpers;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.properties.CatalogMetaProperties;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

public class TestCatalogBuilder {

  static Stream<Arguments> nonIcebergFormats() {
    return Stream.of(
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_HIVE, TableFormat.MIXED_ICEBERG),
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_HIVE, TableFormat.MIXED_HIVE),
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_HIVE, TableFormat.PAIMON),
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_HIVE, TableFormat.HUDI),
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_CUSTOM, TableFormat.MIXED_ICEBERG),
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_REST, TableFormat.MIXED_ICEBERG),
        Arguments.of(CatalogMetaProperties.CATALOG_TYPE_GLUE, TableFormat.MIXED_ICEBERG));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("nonIcebergFormats")
  public void rejectsNonIcebergTableFormat(String catalogType, TableFormat format) {
    CatalogMeta meta =
        CatalogTestHelpers.buildCatalogMeta("test_catalog", catalogType, Maps.newHashMap(), format);

    IllegalStateException e =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> CatalogBuilder.buildServerCatalog(meta, new Configurations()));
    Assertions.assertTrue(
        e.getMessage().contains(format.name()),
        "message should name the format, was: " + e.getMessage());
  }

  @Test
  public void acceptsIcebergOnlyCatalog(@TempDir Path warehouse) {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(CatalogMetaProperties.KEY_WAREHOUSE, warehouse.toString());
    CatalogMeta meta =
        CatalogTestHelpers.buildCatalogMeta(
            "test_catalog",
            CatalogMetaProperties.CATALOG_TYPE_HADOOP,
            properties,
            TableFormat.ICEBERG);

    ServerCatalog catalog = CatalogBuilder.buildServerCatalog(meta, new Configurations());

    Assertions.assertInstanceOf(ExternalCatalog.class, catalog);
  }
}
