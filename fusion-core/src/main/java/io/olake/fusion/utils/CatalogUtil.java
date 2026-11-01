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

package io.olake.fusion.utils;

import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.table.TableIdentifier;
import io.olake.fusion.table.TableMetaStore;
import io.olake.fusion.table.TableProperties;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CachingCatalog;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.aws.glue.GlueCatalog;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class CatalogUtil {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogUtil.class);

  /** Return table format set catalog supported. */
  public static Set<TableFormat> tableFormats(CatalogMeta meta) {
    return tableFormats(meta.getCatalogType(), meta.getCatalogProperties());
  }

  /** Return table format set catalog supported. */
  public static Set<TableFormat> tableFormats(
      String metastoreType, Map<String, String> catalogProperties) {
    Set<TableFormat> parsedFormats = null;
    if (catalogProperties != null
        && catalogProperties.containsKey(CatalogMetaProperties.TABLE_FORMATS)) {
      String tableFormatsProperty = catalogProperties.get(CatalogMetaProperties.TABLE_FORMATS);
      if (tableFormatsProperty != null) {
        parsedFormats =
            Arrays.stream(tableFormatsProperty.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> TableFormat.valueOf(s.toUpperCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
      }
    }

    if (parsedFormats != null && !parsedFormats.isEmpty()) {
      return parsedFormats;
    }

    // Generate table format from catalog type for compatibility with older versions
    switch (metastoreType) {
      case CatalogMetaProperties.CATALOG_TYPE_CUSTOM:
      case CatalogMetaProperties.CATALOG_TYPE_REST:
      case CatalogMetaProperties.CATALOG_TYPE_HADOOP:
      case CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM:
      case CatalogMetaProperties.CATALOG_TYPE_GLUE:
      case CatalogMetaProperties.CATALOG_TYPE_HIVE:
        return Sets.newHashSet(TableFormat.ICEBERG);
      default:
        throw new IllegalArgumentException("Unsupported catalog type:" + metastoreType);
    }
  }

  /** Merge catalog properties in client side into catalog meta. */
  public static void mergeCatalogProperties(CatalogMeta meta, Map<String, String> properties) {
    if (meta.getCatalogProperties() == null) {
      meta.setCatalogProperties(Maps.newHashMap());
    }
    if (properties != null) {
      properties.forEach(meta::putToCatalogProperties);
    }
  }

  /**
   * merge properties of table level in catalog properties to table(properties key start with
   * table.)
   *
   * @param tableProperties properties in table
   * @param catalogProperties properties in catalog
   * @return merged table properties
   */
  public static Map<String, String> mergeCatalogPropertiesToTable(
      Map<String, String> tableProperties, Map<String, String> catalogProperties) {
    Map<String, String> mergedProperties =
        catalogProperties.entrySet().stream()
            .filter(e -> e.getKey().startsWith(CatalogMetaProperties.TABLE_PROPERTIES_PREFIX))
            .collect(
                Collectors.toMap(
                    e ->
                        e.getKey()
                            .substring(CatalogMetaProperties.TABLE_PROPERTIES_PREFIX.length()),
                    Map.Entry::getValue));

    if (!PropertyUtil.propertyAsBoolean(
        tableProperties,
        TableProperties.ENABLE_LOG_STORE,
        TableProperties.ENABLE_LOG_STORE_DEFAULT)) {
      mergedProperties =
          mergedProperties.entrySet().stream()
              .filter(
                  e -> !e.getKey().startsWith(CatalogMetaProperties.LOG_STORE_PROPERTIES_PREFIX))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    String optimizationEnabled =
        tableProperties.getOrDefault(
            TableProperties.ENABLE_SELF_OPTIMIZING,
            mergedProperties.getOrDefault(
                TableProperties.ENABLE_SELF_OPTIMIZING,
                String.valueOf(TableProperties.ENABLE_SELF_OPTIMIZING_DEFAULT)));
    if (!Boolean.parseBoolean(optimizationEnabled)) {
      mergedProperties =
          mergedProperties.entrySet().stream()
              .filter(e -> !e.getKey().startsWith(CatalogMetaProperties.OPTIMIZE_PROPERTIES_PREFIX))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      // maintain 'optimize.enable' flag as false in table properties
      mergedProperties.put(TableProperties.ENABLE_SELF_OPTIMIZING, optimizationEnabled);
    }
    mergedProperties.putAll(tableProperties);

    return mergedProperties;
  }

  /**
   * add initialize properties for iceberg catalog
   *
   * @param catalogName - catalog name
   * @param metastoreType - metastore type
   * @param properties - catalog properties
   * @return catalog properties with initialize properties.
   */
  public static Map<String, String> withIcebergCatalogInitializeProperties(
      String catalogName, String metastoreType, Map<String, String> properties) {
    Map<String, String> icebergCatalogProperties = Maps.newHashMap(properties);
    icebergCatalogProperties.put(
        org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE,
        CatalogUtil.normalizeCatalogType(metastoreType));
    if (CatalogMetaProperties.CATALOG_TYPE_GLUE.equals(metastoreType)) {
      icebergCatalogProperties.put(CatalogProperties.CATALOG_IMPL, GlueCatalog.class.getName());
    }
    if (CatalogMetaProperties.CATALOG_TYPE_REST.equals(metastoreType)) {
      icebergCatalogProperties.put(CatalogProperties.CATALOG_IMPL, RESTCatalog.class.getName());
    }

    if (CatalogMetaProperties.CATALOG_TYPE_CUSTOM.equalsIgnoreCase(metastoreType)) {
      Preconditions.checkArgument(
          icebergCatalogProperties.containsKey(CatalogProperties.CATALOG_IMPL),
          "Custom catalog properties must contains " + CatalogProperties.CATALOG_IMPL);
    }

    if (icebergCatalogProperties.containsKey(CatalogProperties.CATALOG_IMPL)) {
      icebergCatalogProperties.remove(org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE);
    }

    return icebergCatalogProperties;
  }

  /**
   * Build cache catalog.
   *
   * @param catalog The catalog of the wrap that needs to be cached
   * @param properties table properties
   * @return If Cache is enabled, CachingCatalog is returned, otherwise, the input catalog is
   *     returned
   */
  public static Catalog buildCacheCatalog(Catalog catalog, Map<String, String> properties) {
    boolean cacheEnabled =
        PropertyUtil.propertyAsBoolean(
            properties, CatalogProperties.CACHE_ENABLED, CatalogProperties.CACHE_ENABLED_DEFAULT);

    boolean cacheCaseSensitive =
        PropertyUtil.propertyAsBoolean(
            properties,
            CatalogProperties.CACHE_CASE_SENSITIVE,
            CatalogProperties.CACHE_CASE_SENSITIVE_DEFAULT);

    long cacheExpirationIntervalMs =
        PropertyUtil.propertyAsLong(
            properties,
            CatalogProperties.CACHE_EXPIRATION_INTERVAL_MS,
            CatalogProperties.CACHE_EXPIRATION_INTERVAL_MS_DEFAULT);

    // An expiration interval of 0ms effectively disables caching.
    // Do not wrap with CachingCatalog.
    if (cacheExpirationIntervalMs <= 0) {
      LOG.warn(
          "Configuration `{}` is {}, less than or equal to 0, then the cache will not take effect.",
          CatalogProperties.CACHE_EXPIRATION_INTERVAL_MS,
          cacheExpirationIntervalMs);
      cacheEnabled = false;
    }
    return cacheEnabled
        ? CachingCatalog.wrap(catalog, cacheCaseSensitive, cacheExpirationIntervalMs)
        : catalog;
  }

  /**
   * Build {@link TableMetaStore} from catalog meta.
   *
   * <p>Storage type {@code Local} uses the default Hadoop configuration without authentication.
   * Every other storage (S3) authenticates through the catalog's FileIO properties; only the AK/SK
   * auth type is recorded in the meta store.
   */
  public static TableMetaStore buildMetaStore(CatalogMeta catalogMeta) {
    TableMetaStore.Builder builder = TableMetaStore.builder();
    Map<String, String> storageConfigs = catalogMeta.getStorageConfigs();
    if (storageConfigs != null
        && CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_LOCAL.equalsIgnoreCase(
            storageConfigs.get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_TYPE))) {
      return builder.withConfiguration(new Configuration()).build();
    }

    boolean loadAuthFromAMS =
        propertyAsBoolean(
            catalogMeta.getCatalogProperties(),
            CatalogMetaProperties.LOAD_AUTH_FROM_AMS,
            CatalogMetaProperties.LOAD_AUTH_FROM_AMS_DEFAULT);
    // load auth configs from ams
    if (loadAuthFromAMS && catalogMeta.getAuthConfigs() != null) {
      String authType =
          catalogMeta.getAuthConfigs().get(CatalogMetaProperties.AUTH_CONFIGS_KEY_TYPE);
      LOG.info("TableMetaStore use auth config in catalog meta, authType is {}", authType);
      applyAkSkAuth(builder, catalogMeta.getAuthConfigs(), storageConfigs);
    }

    // cover auth configs from ams with auth configs in properties
    String authType =
        catalogMeta.getCatalogProperties().get(CatalogMetaProperties.AUTH_CONFIGS_KEY_TYPE);
    if (StringUtils.isNotEmpty(authType)) {
      LOG.info("TableMetaStore use auth config in properties, authType is {}", authType);
      applyAkSkAuth(builder, catalogMeta.getCatalogProperties(), storageConfigs);
    }
    return builder.build();
  }

  private static void applyAkSkAuth(
      TableMetaStore.Builder builder,
      Map<String, String> authConfigs,
      Map<String, String> storageConfigs) {
    if (CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_AK_SK.equalsIgnoreCase(
        authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_TYPE))) {
      builder.withAkSkAuth(
          authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_ACCESS_KEY),
          authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_SECRET_KEY),
          storageConfigs == null
              ? null
              : storageConfigs.get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_TYPE));
    }
  }

  public static io.olake.fusion.api.TableIdentifier amsTableId(TableIdentifier tableIdentifier) {
    return new io.olake.fusion.api.TableIdentifier(
        tableIdentifier.getCatalog(),
        tableIdentifier.getDatabase(),
        tableIdentifier.getTableName());
  }

  /**
   * Copy property from source properties to target properties, support changing the key name.
   *
   * @param fromProperties - from these properties
   * @param toProperties - to these properties
   * @param fromKey - from key
   * @param toKey - to key
   */
  public static <T> void copyProperty(
      Map<String, String> fromProperties,
      Map<String, T> toProperties,
      String fromKey,
      String toKey) {
    if (StringUtils.isNotEmpty(fromProperties.get(fromKey))) {
      toProperties.put(toKey, (T) fromProperties.get(fromKey));
    }
  }

  public static String normalizeMetastoreType(String type) {
    if (type == null) {
      return null;
    }
    if (CatalogMetaProperties.CATALOG_TYPE_HADOOP.equalsIgnoreCase(type)
        || CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM.equalsIgnoreCase(type)) {
      return CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM;
    }
    return type;
  }

  /**
   * Normalize catalog type for client-side and factory usage.
   *
   * <p>Iceberg only recognizes short catalog types like "hadoop" or "hive" in its {@code
   * CatalogUtil}, and does not recognize "filesystem" yet. To keep AMS using "filesystem" in
   * metadata while still working with Iceberg, we normalize "filesystem" back to "hadoop" before
   * passing it down to Iceberg.
   */
  public static String normalizeCatalogType(String type) {
    if (type == null) {
      return null;
    }
    if (CatalogMetaProperties.CATALOG_TYPE_FILESYSTEM.equalsIgnoreCase(type)) {
      return CatalogMetaProperties.CATALOG_TYPE_HADOOP;
    }
    return type;
  }

  private static boolean propertyAsBoolean(
      Map<String, String> properties, String property, boolean defaultValue) {
    String value = properties.get(property);
    if (value != null) {
      return Boolean.parseBoolean(value);
    }
    return defaultValue;
  }
}
