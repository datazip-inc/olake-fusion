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
 */

package org.apache.amoro.server.catalog;

import static org.apache.amoro.server.table.internal.InternalTableConstants.GCS_FILE_IO_IMPL;

import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.amoro.utils.CatalogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.gcp.GCPProperties;
import org.apache.iceberg.io.FileIO;

import java.util.HashMap;
import java.util.Map;

/** GCS FileIO setup for external catalogs, including managed REST catalogs on GCS. */
public class CatalogFileIoUtil {

  public static final String ICEBERG_ACCESS_DELEGATION_HEADER_PROPERTY =
      "header.X-Iceberg-Access-Delegation";

  private static final String VENDED_CREDENTIALS = "vended-credentials";

  private CatalogFileIoUtil() {}

  public static boolean isGcsStorage(CatalogMeta catalogMeta) {
    if (catalogMeta == null || catalogMeta.getStorageConfigs() == null) {
      return false;
    }
    return CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_GCS.equalsIgnoreCase(
        CatalogUtil.getCompatibleStorageType(catalogMeta.getStorageConfigs()));
  }

  /**
   * Apply GCS Iceberg FileIO properties: {@code io-impl}, optional {@code gcs.project-id}, and for
   * REST catalogs the credential-vending header expected by managed catalogs (e.g. Unity Catalog).
   */
  public static void applyExternalCatalogFileIoProperties(
      CatalogMeta catalogMeta, Map<String, String> properties) {
    if (!isGcsStorage(catalogMeta) || properties == null) {
      return;
    }
    properties.put(CatalogProperties.FILE_IO_IMPL, GCS_FILE_IO_IMPL);
    Map<String, String> catalogProperties = catalogMeta.getCatalogProperties();
    if (catalogProperties != null
        && StringUtils.isNotEmpty(catalogProperties.get(GCPProperties.GCS_PROJECT_ID))) {
      properties.put(
          GCPProperties.GCS_PROJECT_ID, catalogProperties.get(GCPProperties.GCS_PROJECT_ID));
    }
    if (CatalogMetaProperties.CATALOG_TYPE_REST.equalsIgnoreCase(
        CatalogUtil.normalizeMetastoreType(catalogMeta.getCatalogType()))) {
      properties.putIfAbsent(ICEBERG_ACCESS_DELEGATION_HEADER_PROPERTY, VENDED_CREDENTIALS);
    }
  }

  /**
   * Load {@link org.apache.iceberg.gcp.gcs.GCSFileIO} for catalog file access. Merges catalog
   * properties with table-level FileIO properties (e.g. vended {@code gcs.oauth2.token} from REST
   * catalog).
   */
  public static FileIO loadGcsFileIo(
      CatalogMeta catalogMeta,
      Map<String, String> icebergCatalogProperties,
      Map<String, String> tableFileIoProperties,
      Configuration configuration) {
    Map<String, String> fileIoProperties = new HashMap<>();
    if (icebergCatalogProperties != null) {
      fileIoProperties.putAll(icebergCatalogProperties);
    }
    if (tableFileIoProperties != null) {
      fileIoProperties.putAll(tableFileIoProperties);
    }
    applyExternalCatalogFileIoProperties(catalogMeta, fileIoProperties);
    return org.apache.iceberg.CatalogUtil.loadFileIO(
        GCS_FILE_IO_IMPL, fileIoProperties, configuration);
  }
}
