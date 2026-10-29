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

package io.olake.fusion.table.descriptor;

import io.olake.fusion.FusionTable;
import io.olake.fusion.TableFormat;
import io.olake.fusion.process.ProcessStatus;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** API for obtaining metadata information of various formats. */
public interface FormatTableDescriptor {

  /**
   * Global io-executor pool for table descriptor
   *
   * @param ioExecutor io executor pool.
   */
  void withIoExecutor(ExecutorService ioExecutor);

  /** Get the format supported by this descriptor. */
  List<TableFormat> supportFormat();

  /** Get the table metadata information of the {@link FusionTable}. */
  ServerTableMeta getTableDetail(FusionTable<?> amoroTable);

  /** Get the snapshot information of the {@link FusionTable}. */
  List<FusionSnapshotsOfTable> getSnapshots(
      FusionTable<?> amoroTable, String ref, OperationType operationType);

  /** Get the snapshot detail information of the {@link FusionTable}. */
  List<PartitionFileBaseInfo> getSnapshotDetail(
      FusionTable<?> amoroTable, String snapshotId, String ref);

  /** Get the DDL information of the {@link FusionTable}. */
  List<DDLInfo> getTableOperations(FusionTable<?> amoroTable);

  /** Get the partition information of the {@link FusionTable}. */
  List<PartitionBaseInfo> getTablePartitions(FusionTable<?> amoroTable);

  /** Get the file information of the {@link FusionTable}. */
  List<PartitionFileBaseInfo> getTableFiles(
      FusionTable<?> amoroTable, String partition, Integer specId);

  /** Get the paged optimizing process information of the {@link FusionTable} and total size. */
  Pair<List<OptimizingProcessInfo>, Integer> getOptimizingProcessesInfo(
      FusionTable<?> amoroTable, String type, ProcessStatus status, int limit, int offset);

  /** Return the optimizing types of the {@link FusionTable} is supported. */
  Map<String, String> getTableOptimizingTypes(FusionTable<?> amoroTable);

  /** Get the paged optimizing process tasks information of the {@link FusionTable}. */
  List<OptimizingTaskInfo> getOptimizingTaskInfos(FusionTable<?> amoroTable, String processId);

  /** Get the tag information of the {@link FusionTable}. */
  List<TagOrBranchInfo> getTableTags(FusionTable<?> amoroTable);

  /** Get the branch information of the {@link FusionTable}. */
  List<TagOrBranchInfo> getTableBranches(FusionTable<?> amoroTable);

  /** Get the consumer information of the {@link FusionTable}. */
  List<ConsumerInfo> getTableConsumerInfos(FusionTable<?> amoroTable);
}
