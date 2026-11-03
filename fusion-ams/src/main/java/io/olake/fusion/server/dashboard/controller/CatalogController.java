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

package io.olake.fusion.server.dashboard.controller;

import static io.olake.fusion.TableFormat.ICEBERG;
import static io.olake.fusion.properties.CatalogMetaProperties.AUTH_CONFIGS_KEY_ACCESS_KEY;
import static io.olake.fusion.properties.CatalogMetaProperties.AUTH_CONFIGS_KEY_SECRET_KEY;
import static io.olake.fusion.properties.CatalogMetaProperties.AUTH_CONFIGS_KEY_TYPE;
import static io.olake.fusion.properties.CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_AK_SK;
import static io.olake.fusion.properties.CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_CUSTOM;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_CUSTOM;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_GLUE;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_HIVE;
import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_REST;
import static io.olake.fusion.properties.CatalogMetaProperties.STORAGE_CONFIGS_KEY_REGION;
import static io.olake.fusion.properties.CatalogMetaProperties.STORAGE_CONFIGS_KEY_S3_ENDPOINT;
import static io.olake.fusion.properties.CatalogMetaProperties.STORAGE_CONFIGS_KEY_TYPE;
import static io.olake.fusion.properties.CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_LOCAL;
import static io.olake.fusion.properties.CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_S3;
import static io.olake.fusion.properties.CatalogMetaProperties.TABLE_FORMATS;

import io.javalin.http.Context;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.server.catalog.CatalogManager;
import io.olake.fusion.server.dashboard.model.CatalogRegisterInfo;
import io.olake.fusion.server.dashboard.model.CatalogSettingInfo;
import io.olake.fusion.server.dashboard.response.OkResponse;
import io.olake.fusion.server.dashboard.utils.DesensitizationUtil;
import io.olake.fusion.server.dashboard.utils.PropertiesUtil;
import io.olake.fusion.table.TableProperties;
import io.olake.fusion.utils.CatalogUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Objects;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.glue.GlueCatalog;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.rest.RESTCatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The controller that handles catalog requests. */
public class CatalogController {
  /** Hadoop auth types Fusion no longer supports; catalogs must use AK/SK or custom auth. */
  private static final Set<String> REMOVED_AUTH_TYPES = Sets.newHashSet("simple", "kerberos");

  private static final Map<String, List<String>> CATALOG_REQUIRED_PROPERTIES;
  private static final Set<CatalogDescriptor> VALIDATE_CATALOGS;

  static {
    CATALOG_REQUIRED_PROPERTIES = Maps.newHashMap();
    CATALOG_REQUIRED_PROPERTIES.put(
        CATALOG_TYPE_GLUE, Lists.newArrayList(CatalogProperties.WAREHOUSE_LOCATION));
    CATALOG_REQUIRED_PROPERTIES.put(
        CATALOG_TYPE_CUSTOM, Lists.newArrayList(CatalogProperties.CATALOG_IMPL));
    CATALOG_REQUIRED_PROPERTIES.put(CATALOG_TYPE_REST, Lists.newArrayList(CatalogProperties.URI));
  }

  static {
    VALIDATE_CATALOGS = Sets.newHashSet();
    // OLake-UI registers every catalog with S3 storage.
    VALIDATE_CATALOGS.add(
        CatalogDescriptor.of(CATALOG_TYPE_HIVE, STORAGE_CONFIGS_VALUE_TYPE_S3, ICEBERG));
    VALIDATE_CATALOGS.add(
        CatalogDescriptor.of(CATALOG_TYPE_GLUE, STORAGE_CONFIGS_VALUE_TYPE_S3, ICEBERG));
    VALIDATE_CATALOGS.add(
        CatalogDescriptor.of(CATALOG_TYPE_CUSTOM, STORAGE_CONFIGS_VALUE_TYPE_S3, ICEBERG));
    VALIDATE_CATALOGS.add(
        CatalogDescriptor.of(CATALOG_TYPE_REST, STORAGE_CONFIGS_VALUE_TYPE_S3, ICEBERG));
    // Custom catalogs on the local file system, used for local testing.
    VALIDATE_CATALOGS.add(
        CatalogDescriptor.of(CATALOG_TYPE_CUSTOM, STORAGE_CONFIGS_VALUE_TYPE_LOCAL, ICEBERG));
  }

  private final CatalogManager catalogService;

  public CatalogController(CatalogManager catalogService) {
    this.catalogService = catalogService;
  }

  private static Set<String> getHiddenCatalogTableProperties() {
    return Sets.newHashSet(TableProperties.SELF_OPTIMIZING_GROUP);
  }

  private static Set<String> getHiddenCatalogProperties(CatalogRegisterInfo info) {
    return getHiddenCatalogProperties(
        info.getType(), info.getAuthConfig(), info.getStorageConfig());
  }

  private static Set<String> getHiddenCatalogProperties(CatalogSettingInfo info) {
    return getHiddenCatalogProperties(
        info.getType(), info.getAuthConfig(), info.getStorageConfig());
  }

  private static Set<String> getHiddenCatalogProperties(
      String type, Map<String, ?> authConfig, Map<String, ?> storageConfig) {
    Set<String> hiddenProperties = Sets.newHashSet(TABLE_FORMATS);
    if (!CATALOG_TYPE_CUSTOM.equals(type)) {
      hiddenProperties.add(CatalogProperties.CATALOG_IMPL);
    }
    if (AUTH_CONFIGS_VALUE_TYPE_AK_SK.equalsIgnoreCase(
        String.valueOf(authConfig.get(AUTH_CONFIGS_KEY_TYPE)))) {
      hiddenProperties.add(S3FileIOProperties.ACCESS_KEY_ID);
      hiddenProperties.add(S3FileIOProperties.SECRET_ACCESS_KEY);
    }
    if (STORAGE_CONFIGS_VALUE_TYPE_S3.equals(storageConfig.get(STORAGE_CONFIGS_KEY_TYPE))) {
      hiddenProperties.add(AwsClientProperties.CLIENT_REGION);
      hiddenProperties.add(S3FileIOProperties.ENDPOINT);
    }
    return hiddenProperties;
  }

  /** Get catalog Type list */
  public void getCatalogTypeList(Context ctx) {

    List<ImmutableMap<String, String>> catalogTypes = new ArrayList<>();
    String valueKey = "value";
    String displayKey = "display";
    catalogTypes.add(ImmutableMap.of(valueKey, CATALOG_TYPE_HIVE, displayKey, "Hive"));
    catalogTypes.add(ImmutableMap.of(valueKey, CATALOG_TYPE_GLUE, displayKey, "Glue"));
    catalogTypes.add(ImmutableMap.of(valueKey, CATALOG_TYPE_REST, displayKey, "REST"));
    catalogTypes.add(ImmutableMap.of(valueKey, CATALOG_TYPE_CUSTOM, displayKey, "Custom"));
    ctx.json(OkResponse.of(catalogTypes));
  }

  private void fillAuthConfigs2CatalogMeta(
      CatalogMeta catalogMeta, Map<String, String> serverAuthConfig) {
    Map<String, String> metaAuthConfig = new HashMap<>();
    String authType =
        serverAuthConfig
            .getOrDefault(AUTH_CONFIGS_KEY_TYPE, AUTH_CONFIGS_VALUE_TYPE_CUSTOM)
            .toLowerCase();
    metaAuthConfig.put(AUTH_CONFIGS_KEY_TYPE, authType);
    if (AUTH_CONFIGS_VALUE_TYPE_AK_SK.equals(authType)) {
      metaAuthConfig.put(
          AUTH_CONFIGS_KEY_ACCESS_KEY, serverAuthConfig.get(AUTH_CONFIGS_KEY_ACCESS_KEY));
      metaAuthConfig.put(
          AUTH_CONFIGS_KEY_SECRET_KEY, serverAuthConfig.get(AUTH_CONFIGS_KEY_SECRET_KEY));

      CatalogUtil.copyProperty(
          serverAuthConfig,
          catalogMeta.getCatalogProperties(),
          AUTH_CONFIGS_KEY_ACCESS_KEY,
          S3FileIOProperties.ACCESS_KEY_ID);
      CatalogUtil.copyProperty(
          serverAuthConfig,
          catalogMeta.getCatalogProperties(),
          AUTH_CONFIGS_KEY_SECRET_KEY,
          S3FileIOProperties.SECRET_ACCESS_KEY);
    }
    catalogMeta.setAuthConfigs(metaAuthConfig);
  }

  private Map<String, Object> extractAuthConfigsFromCatalogMeta(CatalogMeta catalogMeta) {
    Map<String, Object> serverAuthConfig = new HashMap<>();
    Map<String, String> metaAuthConfig = catalogMeta.getAuthConfigs();
    String authType =
        metaAuthConfig.getOrDefault(AUTH_CONFIGS_KEY_TYPE, AUTH_CONFIGS_VALUE_TYPE_CUSTOM);
    serverAuthConfig.put(AUTH_CONFIGS_KEY_TYPE, authType.toUpperCase());
    if (AUTH_CONFIGS_VALUE_TYPE_AK_SK.equals(authType)) {
      CatalogUtil.copyProperty(
          catalogMeta.getCatalogProperties(),
          serverAuthConfig,
          S3FileIOProperties.ACCESS_KEY_ID,
          AUTH_CONFIGS_KEY_ACCESS_KEY);
      CatalogUtil.copyProperty(
          catalogMeta.getCatalogProperties(),
          serverAuthConfig,
          S3FileIOProperties.SECRET_ACCESS_KEY,
          AUTH_CONFIGS_KEY_SECRET_KEY);
    }
    return serverAuthConfig;
  }

  private Map<String, Object> extractStorageConfigsFromCatalogMeta(
      CatalogMeta catalogMeta, String storageType) {
    Map<String, Object> storageConfig = new HashMap<>();
    storageConfig.put(STORAGE_CONFIGS_KEY_TYPE, storageType);
    if (STORAGE_CONFIGS_VALUE_TYPE_S3.equals(storageType)) {
      CatalogUtil.copyProperty(
          catalogMeta.getCatalogProperties(),
          storageConfig,
          AwsClientProperties.CLIENT_REGION,
          STORAGE_CONFIGS_KEY_REGION);
      CatalogUtil.copyProperty(
          catalogMeta.getCatalogProperties(),
          storageConfig,
          S3FileIOProperties.ENDPOINT,
          STORAGE_CONFIGS_KEY_S3_ENDPOINT);
    }
    return storageConfig;
  }

  /** Construct catalog meta through catalog register info. */
  private CatalogMeta constructCatalogMeta(CatalogRegisterInfo info) {
    CatalogMeta catalogMeta = new CatalogMeta();
    catalogMeta.setCatalogName(info.getName());
    String metastoreType = info.getType();
    catalogMeta.setCatalogType(metastoreType);
    catalogMeta.setCatalogProperties(
        PropertiesUtil.unionCatalogProperties(info.getTableProperties(), info.getProperties()));
    // fill catalog impl when catalog type is glue or rest
    if (CatalogMetaProperties.CATALOG_TYPE_GLUE.equals(metastoreType)) {
      catalogMeta.putToCatalogProperties(
          CatalogProperties.CATALOG_IMPL, GlueCatalog.class.getName());
    } else if (CatalogMetaProperties.CATALOG_TYPE_REST.equals(metastoreType)) {
      catalogMeta.putToCatalogProperties(
          CatalogProperties.CATALOG_IMPL, RESTCatalog.class.getName());
    }

    catalogMeta.putToCatalogProperties(
        CatalogMetaProperties.TABLE_PROPERTIES_PREFIX + TableProperties.SELF_OPTIMIZING_GROUP,
        info.getOptimizerGroup());
    String tableFormats;
    try {
      // validate table format
      tableFormats =
          info.getTableFormatList().stream()
              .map(item -> TableFormat.valueOf(item).name())
              .collect(Collectors.joining(","));
    } catch (Exception e) {
      throw new RuntimeException(
          "Invalid table format list, " + String.join(",", info.getTableFormatList()));
    }
    catalogMeta.getCatalogProperties().put(CatalogMetaProperties.TABLE_FORMATS, tableFormats);
    String storageType =
        info.getStorageConfig()
            .getOrDefault(STORAGE_CONFIGS_KEY_TYPE, STORAGE_CONFIGS_VALUE_TYPE_S3);
    fillAuthConfigs2CatalogMeta(catalogMeta, info.getAuthConfig());
    Map<String, String> metaStorageConfig = new HashMap<>();
    metaStorageConfig.put(STORAGE_CONFIGS_KEY_TYPE, storageType);
    if (storageType.equals(STORAGE_CONFIGS_VALUE_TYPE_S3)) {
      CatalogUtil.copyProperty(
          info.getStorageConfig(),
          catalogMeta.getCatalogProperties(),
          STORAGE_CONFIGS_KEY_REGION,
          AwsClientProperties.CLIENT_REGION);
      CatalogUtil.copyProperty(
          info.getStorageConfig(),
          catalogMeta.getCatalogProperties(),
          STORAGE_CONFIGS_KEY_S3_ENDPOINT,
          S3FileIOProperties.ENDPOINT);
    } else if (!storageType.equals(STORAGE_CONFIGS_VALUE_TYPE_LOCAL)) {
      // Local storage type does not require additional storage configs.
      throw new RuntimeException("Invalid storage type " + storageType);
    }

    catalogMeta.setStorageConfigs(metaStorageConfig);
    return catalogMeta;
  }

  private void checkHiddenProperties(CatalogRegisterInfo info) {
    getHiddenCatalogTableProperties().stream()
        .filter(info.getTableProperties()::containsKey)
        .findAny()
        .ifPresent(
            hiddenCatalogTableProperty -> {
              throw new IllegalArgumentException(
                  String.format(
                      "Table property %s is not allowed to set", hiddenCatalogTableProperty));
            });
    getHiddenCatalogProperties(info).stream()
        .filter(info.getProperties()::containsKey)
        .findAny()
        .ifPresent(
            hiddenCatalogProperty -> {
              throw new IllegalArgumentException(
                  String.format(
                      "Catalog property %s is not allowed to set", hiddenCatalogProperty));
            });
  }

  private void removeHiddenProperties(CatalogSettingInfo info) {
    getHiddenCatalogTableProperties().forEach(info.getTableProperties()::remove);
    getHiddenCatalogProperties(info).forEach(info.getProperties()::remove);
  }

  private void maskSensitiveData(CatalogSettingInfo info) {
    if (info.getAuthConfig().containsKey(AUTH_CONFIGS_KEY_SECRET_KEY)) {
      info.getAuthConfig()
          .put(
              AUTH_CONFIGS_KEY_SECRET_KEY,
              DesensitizationUtil.desensitize(
                  info.getAuthConfig().get(AUTH_CONFIGS_KEY_SECRET_KEY)));
    }
  }

  private void unMaskSensitiveData(CatalogRegisterInfo newInfo, CatalogMeta oldCatalogMeta) {
    if (newInfo.getAuthConfig().containsKey(AUTH_CONFIGS_KEY_SECRET_KEY)) {
      Object secretKey = newInfo.getAuthConfig().get(AUTH_CONFIGS_KEY_SECRET_KEY);
      if (DesensitizationUtil.isDesensitized(secretKey)) {
        Preconditions.checkArgument(
            oldCatalogMeta.getCatalogProperties().containsKey(S3FileIOProperties.SECRET_ACCESS_KEY),
            "Secret key is not set before，must provide a valid secret key");
        newInfo
            .getAuthConfig()
            .put(
                AUTH_CONFIGS_KEY_SECRET_KEY,
                oldCatalogMeta.getCatalogProperties().get(S3FileIOProperties.SECRET_ACCESS_KEY));
      }
    }
  }

  /** Register catalog to ams. */
  public void createCatalog(Context ctx) {
    CatalogRegisterInfo info = ctx.bodyAsClass(CatalogRegisterInfo.class);
    validateCatalogRegisterInfo(info);
    if (catalogService.catalogExist(info.getName())) {
      throw new RuntimeException("Duplicate catalog name!");
    }
    CatalogMeta catalogMeta = constructCatalogMeta(info);
    catalogService.createCatalog(catalogMeta);
    ctx.json(OkResponse.of(""));
  }

  private void validateCatalogRegisterInfo(CatalogRegisterInfo info) {
    Preconditions.checkNotNull(info.getAuthConfig(), "Catalog auth config must not be null");
    Preconditions.checkNotNull(info.getStorageConfig(), "Catalog storage config must not be null");
    Preconditions.checkNotNull(info.getProperties(), "Catalog properties must not be null");
    Preconditions.checkNotNull(
        info.getTableProperties(), "Catalog table properties must not be null");
    Preconditions.checkArgument(
        info.getTableFormatList() != null && !info.getTableFormatList().isEmpty(),
        "Catalog table format list must not be empty");

    CatalogDescriptor.of(info).validate();
    Object authType = info.getAuthConfig().get(AUTH_CONFIGS_KEY_TYPE);
    Preconditions.checkArgument(
        authType == null || !REMOVED_AUTH_TYPES.contains(String.valueOf(authType).toLowerCase()),
        "Auth type %s is not supported, use AK/SK or CUSTOM",
        authType);
    checkHiddenProperties(info);

    List<String> requiredProperties = CATALOG_REQUIRED_PROPERTIES.get(info.getType());
    if (requiredProperties != null && !requiredProperties.isEmpty()) {
      for (String propertyName : requiredProperties) {
        Preconditions.checkArgument(
            info.getProperties().containsKey(propertyName),
            String.format("Catalog type:%s require property:%s.", info.getType(), propertyName));
      }
    }
  }

  /** Get detail of some catalog. */
  public void getCatalogDetail(Context ctx) {
    String catalogName = ctx.pathParam("catalogName");
    CatalogMeta catalogMeta = catalogService.getCatalogMeta(catalogName);
    CatalogSettingInfo info = new CatalogSettingInfo();

    if (catalogService.catalogExist(catalogName)) {
      info.setName(catalogMeta.getCatalogName());
      info.setType(catalogMeta.getCatalogType());

      // we put the table format single
      String tableFormat =
          catalogMeta.getCatalogProperties().get(CatalogMetaProperties.TABLE_FORMATS);
      if (StringUtils.isEmpty(tableFormat)) {
        tableFormat = TableFormat.ICEBERG.name();
      }
      info.setTableFormatList(Arrays.asList(tableFormat.split(",")));

      String storageType = catalogMeta.getStorageConfigs().get(STORAGE_CONFIGS_KEY_TYPE);
      info.setAuthConfig(extractAuthConfigsFromCatalogMeta(catalogMeta));
      info.setStorageConfig(extractStorageConfigsFromCatalogMeta(catalogMeta, storageType));

      info.setProperties(
          PropertiesUtil.extractCatalogMetaProperties(catalogMeta.getCatalogProperties()));
      info.setTableProperties(
          PropertiesUtil.extractTableProperties(catalogMeta.getCatalogProperties()));
      info.setOptimizerGroup(
          info.getTableProperties()
              .getOrDefault(
                  TableProperties.SELF_OPTIMIZING_GROUP,
                  TableProperties.SELF_OPTIMIZING_GROUP_DEFAULT));
      removeHiddenProperties(info);
      maskSensitiveData(info);
      ctx.json(OkResponse.of(info));
      return;
    }
    ctx.json(OkResponse.of(null));
  }

  /** Get detail of some catalog 1、first check whether there are some tables in catalog. */
  public void updateCatalog(Context ctx) {
    CatalogRegisterInfo info = ctx.bodyAsClass(CatalogRegisterInfo.class);
    validateCatalogRegisterInfo(info);
    CatalogMeta optCatalog = catalogService.getCatalogMeta(info.getName());
    Preconditions.checkNotNull(optCatalog, "Catalog not exist!");

    unMaskSensitiveData(info, optCatalog);
    // check only some item can be modified!
    CatalogMeta catalogMeta = constructCatalogMeta(info);
    catalogService.updateCatalog(catalogMeta);
    ctx.json(OkResponse.ok());
  }

  /** Check whether we could delete the catalog */
  public void catalogDeleteCheck(Context ctx) {
    String catalogName = ctx.pathParam("catalogName");
    Preconditions.checkArgument(
        StringUtils.isNotEmpty(ctx.pathParam("catalogName")), "Catalog name is empty!");
    // Fails if the catalog does not exist. Fusion's catalogs are all external, so dropping one
    // never deletes table data.
    catalogService.getCatalogMeta(catalogName);
    ctx.json(OkResponse.of(true));
  }

  /** Delete some catalog and information associate with the catalog */
  public void deleteCatalog(Context ctx) {
    String catalogName = ctx.pathParam("catalogName");
    Preconditions.checkArgument(
        StringUtils.isNotEmpty(ctx.pathParam("catalogName")), "Catalog name is empty!");
    catalogService.dropCatalog(catalogName);
    ctx.json(OkResponse.of("OK"));
  }

  public void getMetastoreTableFormats(Context ctx) {
    String metastoreType = ctx.pathParam("type");
    List<String> tableFormats =
        VALIDATE_CATALOGS.stream()
            .filter(d -> d.catalogType.equalsIgnoreCase(metastoreType))
            .map(d -> d.tableFormat.name())
            .distinct()
            .collect(Collectors.toList());
    ctx.json(OkResponse.of(tableFormats));
  }

  public void getMetastoreStorageTypes(Context ctx) {
    String metastoreType = ctx.pathParam("type");
    List<String> storageTypes =
        VALIDATE_CATALOGS.stream()
            .filter(d -> d.catalogType.equalsIgnoreCase(metastoreType))
            .map(d -> d.storageType)
            .distinct()
            .collect(Collectors.toList());
    ctx.json(OkResponse.of(storageTypes));
  }

  private static class CatalogDescriptor {
    private final String catalogType;
    private final String storageType;
    private final TableFormat tableFormat;

    public CatalogDescriptor(String catalogType, String storageType, TableFormat tableFormat) {
      this.catalogType = catalogType;
      this.storageType = storageType;
      this.tableFormat = tableFormat;
    }

    public static CatalogDescriptor of(
        String catalogType, String storageType, TableFormat tableFormat) {
      return new CatalogDescriptor(catalogType, storageType, tableFormat);
    }

    public static CatalogDescriptor of(CatalogRegisterInfo info) {
      // only support one table format now
      String tableFormat = info.getTableFormatList().get(0);
      String storageType = info.getStorageConfig().get(STORAGE_CONFIGS_KEY_TYPE);
      String catalogType = info.getType();
      return of(catalogType, storageType, TableFormat.valueOf(tableFormat));
    }

    public void validate() {
      Preconditions.checkArgument(VALIDATE_CATALOGS.contains(this), "Not support " + this);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CatalogDescriptor that = (CatalogDescriptor) o;
      return Objects.equal(catalogType, that.catalogType)
          && Objects.equal(storageType, that.storageType)
          && tableFormat == that.tableFormat;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(catalogType, storageType, tableFormat);
    }

    @Override
    public String toString() {
      return String.format(
          "Metastore [%s], Storage Type [%s], Table Format [%s]",
          catalogType, storageType, tableFormat);
    }
  }
}
