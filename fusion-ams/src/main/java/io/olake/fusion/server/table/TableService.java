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

package io.olake.fusion.server.table;

import io.olake.fusion.FusionTable;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableRuntime;
import io.olake.fusion.server.catalog.InternalCatalog;

public interface TableService extends TableRuntimeHandler {

  void addHandlerChain(RuntimeHandlerChain handler);

  void initialize();

  void dispose();

  void onTableCreated(InternalCatalog catalog, ServerTableIdentifier identifier);

  void onTableDropped(InternalCatalog catalog, ServerTableIdentifier identifier);

  TableRuntime getRuntime(Long tableId);

  default boolean contains(Long tableId) {
    return getRuntime(tableId) != null;
  }

  /**
   * load a table via server catalog.
   *
   * @param identifier managed table identifier
   * @return managed table.
   */
  FusionTable<?> loadTable(ServerTableIdentifier identifier);
}
