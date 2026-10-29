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

package io.olake.fusion.server.persistence;

import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.catalog.CatalogTestHelpers;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.server.persistence.mapper.CatalogMetaMapper;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

public class TestPostgresPersistence {

  @ClassRule public static PostgresPersistence POSTGRES = new PostgresPersistence();

  @Test
  public void migrationsRunOnTestPostgres() {
    Assert.assertEquals(
        "every migration must be applied",
        POSTGRES.expectedMigrations(),
        POSTGRES.appliedMigrations());

    CatalogMeta meta =
        CatalogTestHelpers.buildCatalogMeta(
            "pg_catalog_t25",
            CatalogMetaProperties.CATALOG_TYPE_HADOOP,
            Maps.newHashMap(),
            TableFormat.ICEBERG);
    Rows rows = new Rows();
    rows.doAs(CatalogMetaMapper.class, mapper -> mapper.insertCatalog(meta));
    CatalogMeta read =
        rows.getAs(CatalogMetaMapper.class, mapper -> mapper.getCatalog("pg_catalog_t25"));
    Assert.assertEquals(meta.getCatalogName(), read.getCatalogName());
    rows.doAs(CatalogMetaMapper.class, mapper -> mapper.deleteCatalog("pg_catalog_t25"));
  }

  private static class Rows extends PersistentBase {}
}
