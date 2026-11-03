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

package io.olake.fusion.catalog;

import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.properties.CatalogMetaProperties;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.hadoop.HadoopCatalog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class CatalogTestHelpers {

  /**
   * Catalog meta on the local file system: storage type {@code Local}, which uses the default
   * Hadoop configuration, and no authentication. A {@code custom} catalog without {@code
   * catalog-impl} gets Iceberg's {@link HadoopCatalog}, so tests get a catalog that needs only a
   * {@code warehouse} directory.
   */
  public static CatalogMeta buildCatalogMeta(
      String catalogName,
      String type,
      Map<String, String> properties,
      TableFormat... tableFormats) {
    if (CatalogMetaProperties.CATALOG_TYPE_CUSTOM.equals(type)) {
      properties.putIfAbsent(CatalogProperties.CATALOG_IMPL, HadoopCatalog.class.getName());
    }
    Map<String, String> storageConfig = new HashMap<>();
    storageConfig.put(
        CatalogMetaProperties.STORAGE_CONFIGS_KEY_TYPE,
        CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_LOCAL);

    Map<String, String> authConfig = new HashMap<>();

    if (tableFormats != null && tableFormats.length > 0) {
      properties.put(
          CatalogMetaProperties.TABLE_FORMATS,
          Arrays.stream(tableFormats).map(TableFormat::name).collect(Collectors.joining(",")));
    }
    return new CatalogMeta(catalogName, type, storageConfig, authConfig, properties);
  }

  /**
   * Hive catalog meta for a test metastore. {@code properties} must carry the metastore {@code
   * uri}; the client pool size of the test metastore configuration is passed on as {@code clients}.
   */
  public static CatalogMeta buildHiveCatalogMeta(
      String catalogName,
      Map<String, String> properties,
      Configuration hiveConfiguration,
      TableFormat... tableFormats) {
    String clientPoolSize = hiveConfiguration.get("iceberg.hive.client-pool-size");
    if (clientPoolSize != null) {
      properties.putIfAbsent(CatalogMetaProperties.CLIENT_POOL_SIZE, clientPoolSize);
    }
    return buildCatalogMeta(
        catalogName, CatalogMetaProperties.CATALOG_TYPE_HIVE, properties, tableFormats);
  }
}
