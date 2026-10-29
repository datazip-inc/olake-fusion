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

package io.olake.fusion.server.optimizing.maintainer;

import io.olake.fusion.FusionTable;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableRuntime;
import io.olake.fusion.formats.iceberg.maintainer.MixedTableMaintainer;
import io.olake.fusion.maintainer.TableMaintainer;
import io.olake.fusion.server.table.DefaultTableRuntime;
import io.olake.fusion.table.MixedTable;
import org.apache.iceberg.Table;

/** Factory for creating {@link TableMaintainer}. */
@Deprecated
public class TableMaintainers {

  /**
   * Create a {@link TableMaintainer} for the given table.
   *
   * @deprecated since 0.9.0, will be removed in 0.10.0. Use {@link
   *     TableMaintainerFactory#create(FusionTable, TableRuntime)} instead.
   */
  public static TableMaintainer create(FusionTable<?> amoroTable, TableRuntime tableRuntime) {
    return TableMaintainerFactory.create(amoroTable, tableRuntime);
  }

  /**
   * Create a {@link TableMaintainer} for the given table with DefaultTableRuntime.
   *
   * @deprecated since 0.9.0, will be removed in 0.10.0. Use {@link
   *     TableMaintainerFactory#createIcebergMaintainer(Table, DefaultTableRuntime)} instead.
   */
  public static TableMaintainer create(
      FusionTable<?> amoroTable, DefaultTableRuntime tableRuntime) {
    TableFormat format = amoroTable.format();
    if (format.in(TableFormat.MIXED_HIVE, TableFormat.MIXED_ICEBERG)) {
      MixedTable mixedTable = (MixedTable) amoroTable.originalTable();
      return new MixedTableMaintainer(
          mixedTable, new DefaultTableMaintainerContext(tableRuntime, mixedTable));
    } else if (TableFormat.ICEBERG.equals(format)) {
      return TableMaintainerFactory.createIcebergMaintainer(
          (Table) amoroTable.originalTable(), tableRuntime);
    } else {
      throw new RuntimeException("Unsupported table type" + amoroTable.originalTable().getClass());
    }
  }
}
