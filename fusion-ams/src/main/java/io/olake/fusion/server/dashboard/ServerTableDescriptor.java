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

package io.olake.fusion.server.dashboard;

import io.olake.fusion.FusionTable;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.TableIdentifier;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.process.ProcessStatus;
import io.olake.fusion.server.catalog.CatalogManager;
import io.olake.fusion.server.catalog.ServerCatalog;
import io.olake.fusion.server.persistence.PersistentBase;
import io.olake.fusion.server.table.TableManager;
import io.olake.fusion.table.descriptor.ConsumerInfo;
import io.olake.fusion.table.descriptor.DDLInfo;
import io.olake.fusion.table.descriptor.FormatTableDescriptor;
import io.olake.fusion.table.descriptor.FusionSnapshotsOfTable;
import io.olake.fusion.table.descriptor.OperationType;
import io.olake.fusion.table.descriptor.OptimizingProcessInfo;
import io.olake.fusion.table.descriptor.OptimizingTaskInfo;
import io.olake.fusion.table.descriptor.PartitionBaseInfo;
import io.olake.fusion.table.descriptor.PartitionFileBaseInfo;
import io.olake.fusion.table.descriptor.ServerTableMeta;
import io.olake.fusion.table.descriptor.TagOrBranchInfo;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.iceberg.util.ThreadPools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;

public class ServerTableDescriptor extends PersistentBase {

  private final Map<TableFormat, FormatTableDescriptor> formatDescriptorMap = new HashMap<>();

  private final CatalogManager catalogManager;
  private final TableManager tableManager;

  public ServerTableDescriptor(
      CatalogManager catalogManager, TableManager tableManager, Configurations serviceConfig) {
    this.tableManager = tableManager;
    this.catalogManager = catalogManager;

    // All table formats will jointly reuse the work thread pool named iceberg-worker-pool-%d
    ExecutorService executorService = ThreadPools.getWorkerPool();
    ServiceLoader<FormatTableDescriptor> tableDescriptorLoader =
        ServiceLoader.load(FormatTableDescriptor.class);
    for (FormatTableDescriptor descriptor : tableDescriptorLoader) {
      for (TableFormat format : descriptor.supportFormat()) {
        formatDescriptorMap.put(format, descriptor);
      }
      descriptor.withIoExecutor(executorService);
    }
  }

  public ServerTableMeta getTableDetail(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableDetail(amoroTable);
  }

  public List<FusionSnapshotsOfTable> getSnapshots(
      TableIdentifier tableIdentifier, String ref, OperationType operationType) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getSnapshots(amoroTable, ref, operationType);
  }

  public List<PartitionFileBaseInfo> getSnapshotDetail(
      TableIdentifier tableIdentifier, String snapshotId, String ref) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getSnapshotDetail(amoroTable, snapshotId, ref);
  }

  public List<DDLInfo> getTableOperations(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableOperations(amoroTable);
  }

  public List<PartitionBaseInfo> getTablePartition(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTablePartitions(amoroTable);
  }

  public List<PartitionFileBaseInfo> getTableFile(
      TableIdentifier tableIdentifier, String partition, Integer specId) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableFiles(amoroTable, partition, specId);
  }

  public List<TagOrBranchInfo> getTableTags(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableTags(amoroTable);
  }

  public List<TagOrBranchInfo> getTableBranches(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableBranches(amoroTable);
  }

  public List<ConsumerInfo> getTableConsumersInfos(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableConsumerInfos(amoroTable);
  }

  public Pair<List<OptimizingProcessInfo>, Integer> getOptimizingProcessesInfo(
      TableIdentifier tableIdentifier, String type, ProcessStatus status, int limit, int offset) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getOptimizingProcessesInfo(
        amoroTable, type, status, limit, offset);
  }

  public List<OptimizingTaskInfo> getOptimizingProcessTaskInfos(
      TableIdentifier tableIdentifier, String processId) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getOptimizingTaskInfos(amoroTable, processId);
  }

  public Map<String, String> getTableOptimizingTypes(TableIdentifier tableIdentifier) {
    FusionTable<?> amoroTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(amoroTable.format());
    return formatTableDescriptor.getTableOptimizingTypes(amoroTable);
  }

  private FusionTable<?> loadTable(TableIdentifier identifier) {
    ServerCatalog catalog = catalogManager.getServerCatalog(identifier.getCatalog());
    return catalog.loadTable(identifier.getDatabase(), identifier.getTableName());
  }
}
