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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.amoro.TableFormat;
import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.hive.CachedHiveClientPool;
import org.apache.amoro.hive.HMSClientPool;
import org.apache.amoro.hive.utils.HiveTableUtil;
import static org.apache.amoro.properties.CatalogMetaProperties.CATALOG_TYPE_HIVE;
import org.apache.amoro.server.dashboard.model.TableMeta;
import org.apache.amoro.shade.guava32.com.google.common.base.Function;
import org.apache.amoro.table.TableMetaStore;
import org.apache.amoro.utils.CatalogUtil;

/** Shared table-listing logic used by the dashboard API and catalog connection tests. */
public final class CatalogTableListing {

  private CatalogTableListing() {}

  public static List<TableMeta> listTables(ServerCatalog serverCatalog, String database) {
    Function<TableFormat, String> formatToType =
        format -> {
          if (format.equals(TableFormat.MIXED_HIVE) || format.equals(TableFormat.MIXED_ICEBERG)) {
            return TableMeta.TableType.ARCTIC.toString();
          } else if (format.equals(TableFormat.PAIMON)) {
            return TableMeta.TableType.PAIMON.toString();
          } else if (format.equals(TableFormat.ICEBERG)) {
            return TableMeta.TableType.ICEBERG.toString();
          } else if (format.equals(TableFormat.HUDI)) {
            return TableMeta.TableType.HUDI.toString();
          } else {
            return format.toString();
          }
        };

    List<TableMeta> tables =
        new ArrayList<>(
            serverCatalog.listTables(database).stream()
                .map(
                    idWithFormat ->
                        new TableMeta(
                            idWithFormat.getIdentifier().getTableName(),
                            formatToType.apply(idWithFormat.getTableFormat())))
                .sorted(Comparator.comparing(TableMeta::getType).thenComparing(TableMeta::getName))
                .collect(Collectors.toList()));

    String catalogType = serverCatalog.getMetadata().getCatalogType();
    if (Objects.equals(catalogType, CATALOG_TYPE_HIVE)) {
      CatalogMeta catalogMeta = serverCatalog.getMetadata();
      TableMetaStore tableMetaStore = CatalogUtil.buildMetaStore(catalogMeta);
      HMSClientPool hmsClientPool =
          new CachedHiveClientPool(tableMetaStore, catalogMeta.getCatalogProperties());

      List<String> hiveTables = HiveTableUtil.getAllHiveTables(hmsClientPool, database);
      Set<String> mixedHiveTables =
          tables.stream().map(TableMeta::getName).collect(Collectors.toSet());
      hiveTables.stream()
          .filter(e -> !mixedHiveTables.contains(e))
          .sorted(String::compareTo)
          .forEach(e -> tables.add(new TableMeta(e, TableMeta.TableType.HIVE.toString())));
    }

    return tables;
  }
}
