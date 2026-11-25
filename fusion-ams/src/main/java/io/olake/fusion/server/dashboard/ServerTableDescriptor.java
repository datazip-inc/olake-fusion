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
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableDetail(fusionTable);
  }

  public List<FusionSnapshotsOfTable> getSnapshots(
      TableIdentifier tableIdentifier, String ref, OperationType operationType) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getSnapshots(fusionTable, ref, operationType);
  }

  public List<PartitionFileBaseInfo> getSnapshotDetail(
      TableIdentifier tableIdentifier, String snapshotId, String ref) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getSnapshotDetail(fusionTable, snapshotId, ref);
  }

  public List<DDLInfo> getTableOperations(TableIdentifier tableIdentifier) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableOperations(fusionTable);
  }

  public List<PartitionBaseInfo> getTablePartition(TableIdentifier tableIdentifier) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTablePartitions(fusionTable);
  }

  public List<PartitionFileBaseInfo> getTableFile(
      TableIdentifier tableIdentifier, String partition, Integer specId) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableFiles(fusionTable, partition, specId);
  }

  public List<TagOrBranchInfo> getTableTags(TableIdentifier tableIdentifier) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableTags(fusionTable);
  }

  public List<TagOrBranchInfo> getTableBranches(TableIdentifier tableIdentifier) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableBranches(fusionTable);
  }

  public List<ConsumerInfo> getTableConsumersInfos(TableIdentifier tableIdentifier) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableConsumerInfos(fusionTable);
  }

  public Pair<List<OptimizingProcessInfo>, Integer> getOptimizingProcessesInfo(
      TableIdentifier tableIdentifier, String type, ProcessStatus status, int limit, int offset) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getOptimizingProcessesInfo(
        fusionTable, type, status, limit, offset);
  }

  public List<OptimizingTaskInfo> getOptimizingProcessTaskInfos(
      TableIdentifier tableIdentifier, String processId) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getOptimizingTaskInfos(fusionTable, processId);
  }

  public Map<String, String> getTableOptimizingTypes(TableIdentifier tableIdentifier) {
    FusionTable fusionTable = loadTable(tableIdentifier);
    FormatTableDescriptor formatTableDescriptor = formatDescriptorMap.get(fusionTable.format());
    return formatTableDescriptor.getTableOptimizingTypes(fusionTable);
  }

  private FusionTable loadTable(TableIdentifier identifier) {
    ServerCatalog catalog = catalogManager.getServerCatalog(identifier.getCatalog());
    return catalog.loadTable(identifier.getDatabase(), identifier.getTableName());
  }
}
