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

package org.apache.amoro.server.utils;

import static org.apache.amoro.server.table.internal.InternalTableConstants.GCS_FILE_IO_IMPL;
import static org.apache.amoro.server.table.internal.InternalTableConstants.GCS_PROTOCOL_PREFIX;
import static org.apache.amoro.server.table.internal.InternalTableConstants.OSS_FILE_IO_IMPL;
import static org.apache.amoro.server.table.internal.InternalTableConstants.OSS_PROTOCOL_PREFIX;
import static org.apache.amoro.server.table.internal.InternalTableConstants.S3_FILE_IO_IMPL;
import static org.apache.amoro.server.table.internal.InternalTableConstants.S3_PROTOCOL_PREFIX;

import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.amoro.utils.CatalogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.gcp.GCPProperties;

import java.util.Map;

/** Applies Iceberg FileIO defaults from catalog storage config, mirroring {@link InternalTableUtil}. */
public class IcebergCatalogFileIoUtil {

  private IcebergCatalogFileIoUtil() {}

  /**
   * Apply default Iceberg FileIO from warehouse path prefix. Does not override an explicit
   * {@code io-impl}.
   */
  public static void applyDefaultFileIoFromWarehouse(Map<String, String> properties) {
    if (properties == null || properties.containsKey(CatalogProperties.FILE_IO_IMPL)) {
      return;
    }
    String warehouse = properties.get(CatalogMetaProperties.KEY_WAREHOUSE);
    if (StringUtils.isEmpty(warehouse)) {
      warehouse = properties.get(CatalogProperties.WAREHOUSE_LOCATION);
    }
    if (StringUtils.isEmpty(warehouse)) {
      return;
    }
    String normalizedWarehouse = warehouse.toLowerCase();
    if (normalizedWarehouse.startsWith(S3_PROTOCOL_PREFIX)) {
      properties.put(CatalogProperties.FILE_IO_IMPL, S3_FILE_IO_IMPL);
    } else if (normalizedWarehouse.startsWith(OSS_PROTOCOL_PREFIX)) {
      properties.put(CatalogProperties.FILE_IO_IMPL, OSS_FILE_IO_IMPL);
    } else if (normalizedWarehouse.startsWith(GCS_PROTOCOL_PREFIX)) {
      properties.put(CatalogProperties.FILE_IO_IMPL, GCS_FILE_IO_IMPL);
    }
  }

  /**
   * Apply Iceberg FileIO from catalog storage type and properties. GCS storage config sets
   * {@code io-impl} and {@code gcs.project-id} so users do not enter them in catalog properties.
   */
  public static void applyFileIoFromStorage(CatalogMeta catalogMeta, Map<String, String> properties) {
    if (catalogMeta == null || properties == null) {
      return;
    }
    applyDefaultFileIoFromWarehouse(properties);

    Map<String, String> storageConfigs = catalogMeta.getStorageConfigs();
    if (storageConfigs == null) {
      return;
    }
    String storageType = CatalogUtil.getCompatibleStorageType(storageConfigs);
    if (CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_GCS.equalsIgnoreCase(storageType)) {
      properties.put(CatalogProperties.FILE_IO_IMPL, GCS_FILE_IO_IMPL);
      Map<String, String> catalogProperties = catalogMeta.getCatalogProperties();
      if (catalogProperties != null
          && StringUtils.isNotEmpty(catalogProperties.get(GCPProperties.GCS_PROJECT_ID))) {
        properties.put(
            GCPProperties.GCS_PROJECT_ID, catalogProperties.get(GCPProperties.GCS_PROJECT_ID));
      }
    }
  }
}
