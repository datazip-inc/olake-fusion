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
import io.olake.fusion.TableRuntime;
import io.olake.fusion.formats.iceberg.maintainer.IcebergTableMaintainer;
import io.olake.fusion.maintainer.TableMaintainer;
import io.olake.fusion.server.table.DefaultTableRuntime;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;

/** Factory for creating {@link TableMaintainer} instances. */
public class TableMaintainerFactory {

  /**
   * Create an Iceberg table maintainer with AMS context.
   *
   * @param table the table
   * @param tableRuntime the AMS table runtime
   * @return IcebergTableMaintainer instance
   */
  public static IcebergTableMaintainer createIcebergMaintainer(
      FusionTable table, DefaultTableRuntime tableRuntime) {
    return new IcebergTableMaintainer(
        table.table(),
        table.io(),
        tableRuntime.getTableIdentifier().getIdentifier(),
        new DefaultTableMaintainerContext(tableRuntime));
  }

  /**
   * Create a {@link TableMaintainer} for the given table.
   *
   * @param table the table
   * @param tableRuntime the table runtime
   * @return TableMaintainer instance
   */
  public static TableMaintainer create(FusionTable table, TableRuntime tableRuntime) {
    Preconditions.checkArgument(tableRuntime instanceof DefaultTableRuntime);
    return new IcebergTableMaintainer(
        table, new DefaultTableMaintainerContext((DefaultTableRuntime) tableRuntime));
  }
}
