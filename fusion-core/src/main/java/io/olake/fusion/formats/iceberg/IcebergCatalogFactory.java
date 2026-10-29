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

package io.olake.fusion.formats.iceberg;

import io.olake.fusion.FormatCatalog;
import io.olake.fusion.FormatCatalogFactory;
import io.olake.fusion.TableFormat;
import io.olake.fusion.aws.StaticAwsCredentialsProvider;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.table.TableMetaStore;
import io.olake.fusion.utils.MixedFormatCatalogUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;

import java.util.Map;

public class IcebergCatalogFactory implements FormatCatalogFactory {

  @Override
  public FormatCatalog create(
      String name, String metastoreType, Map<String, String> properties, TableMetaStore metaStore) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metastoreType), "metastore type is blank");
    properties =
        MixedFormatCatalogUtil.withIcebergCatalogInitializeProperties(
            name, metastoreType, properties);
    // apply glue credentials to glue catalog
    if (CatalogMetaProperties.CATALOG_TYPE_GLUE.equalsIgnoreCase(metastoreType)) {
      properties = StaticAwsCredentialsProvider.applyGlueCredentials(properties);
    }

    Catalog icebergCatalog =
        CatalogUtil.buildIcebergCatalog(name, properties, metaStore.getConfiguration());
    return new IcebergCatalog(icebergCatalog, properties, metaStore);
  }

  @Override
  public TableFormat format() {
    return TableFormat.ICEBERG;
  }

  @Override
  public Map<String, String> convertCatalogProperties(
      String catalogName, String metastoreType, Map<String, String> unifiedCatalogProperties) {
    return MixedFormatCatalogUtil.withIcebergCatalogInitializeProperties(
        catalogName, metastoreType, unifiedCatalogProperties);
  }
}
