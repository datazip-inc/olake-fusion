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

import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_AMS;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_CUSTOM;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_GLUE;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_HADOOP;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_HIVE;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_REST;

import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.utils.CatalogUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableSet;

import java.util.Set;

public class CatalogBuilder {

  /** Catalog types Fusion builds; every one holds Iceberg tables only. */
  private static final Set<String> SUPPORTED_CATALOG_TYPES =
      ImmutableSet.of(
          CATALOG_TYPE_HADOOP,
          CATALOG_TYPE_FILESYSTEM,
          CATALOG_TYPE_GLUE,
          CATALOG_TYPE_REST,
          CATALOG_TYPE_CUSTOM,
          CATALOG_TYPE_HIVE,
          CATALOG_TYPE_AMS);

  private static final Set<TableFormat> SUPPORTED_TABLE_FORMATS =
      ImmutableSet.of(TableFormat.ICEBERG);

  /** Fails unless the catalog type is supported and its table formats are Iceberg only. */
  public static void checkSupported(CatalogMeta catalogMeta) {
    String type = catalogMeta.getCatalogType();
    Preconditions.checkState(
        SUPPORTED_CATALOG_TYPES.contains(type), "unsupported catalog type: %s", type);
    Set<TableFormat> tableFormats = CatalogUtil.tableFormats(catalogMeta);
    Preconditions.checkState(
        SUPPORTED_TABLE_FORMATS.containsAll(tableFormats),
        "Table format %s is not supported, Fusion supports %s only",
        tableFormats,
        SUPPORTED_TABLE_FORMATS);
  }

  public static ServerCatalog buildServerCatalog(
      CatalogMeta catalogMeta, Configurations serverConfiguration) {
    checkSupported(catalogMeta);
    String type = catalogMeta.getCatalogType();

    switch (type) {
      case CATALOG_TYPE_HADOOP:
      case CATALOG_TYPE_FILESYSTEM:
      case CATALOG_TYPE_GLUE:
      case CATALOG_TYPE_REST:
      case CATALOG_TYPE_CUSTOM:
      case CATALOG_TYPE_HIVE:
        return new ExternalCatalog(catalogMeta);
      case CATALOG_TYPE_AMS:
        return new InternalCatalogImpl(catalogMeta, serverConfiguration);
      default:
        throw new IllegalStateException("unsupported catalog type:" + type);
    }
  }
}
