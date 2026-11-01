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

package io.olake.fusion.server.optimizing.flow;

import static io.olake.fusion.table.TableProperties.SELF_OPTIMIZING_FRAGMENT_RATIO;
import static io.olake.fusion.table.TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_DUPLICATE_RATIO;
import static io.olake.fusion.table.TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_FILE_CNT;
import static io.olake.fusion.table.TableProperties.SELF_OPTIMIZING_TARGET_SIZE;

import io.olake.fusion.FusionTable;
import io.olake.fusion.OptimizerProperties;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.config.OptimizingConfig;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.iceberg.Constants;
import io.olake.fusion.optimizing.IcebergRewriteExecutor;
import io.olake.fusion.optimizing.OptimizingExecutor;
import io.olake.fusion.optimizing.RewriteFilesOutput;
import io.olake.fusion.optimizing.RewriteStageTask;
import io.olake.fusion.optimizing.plan.AbstractOptimizingPlanner;
import io.olake.fusion.server.optimizing.IcebergTableCommit;
import io.olake.fusion.server.optimizing.TaskRuntime;
import io.olake.fusion.server.table.DefaultTableRuntime;
import io.olake.fusion.server.table.TableConfigurations;
import io.olake.fusion.server.utils.IcebergTableUtil;
import io.olake.fusion.utils.PartitionDataFiles;
import io.olake.fusion.utils.TablePropertyUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.util.StructLikeMap;
import org.mockito.Mockito;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class CompleteOptimizingFlow {

  private final Executor executorPool;

  private final int availableCore;

  private final FusionTable fusionTable;

  private final Table table;

  private final List<Checker> checkers;

  private CompleteOptimizingFlow(
      FusionTable fusionTable,
      int availableCore,
      Long targetSize,
      Integer fragmentRatio,
      Double duplicateRatio,
      Integer minorTriggerFileCount,
      List<Checker> checkers) {
    this.executorPool = Executors.newFixedThreadPool(availableCore);
    this.fusionTable = fusionTable;
    this.table = fusionTable.table();
    this.availableCore = availableCore;
    this.checkers = checkers;

    UpdateProperties updateProperties = table.updateProperties();
    if (targetSize != null) {
      updateProperties.set(SELF_OPTIMIZING_TARGET_SIZE, targetSize + "");
    }
    if (fragmentRatio != null) {
      updateProperties.set(SELF_OPTIMIZING_FRAGMENT_RATIO, fragmentRatio + "");
    }
    if (duplicateRatio != null) {
      updateProperties.set(SELF_OPTIMIZING_MAJOR_TRIGGER_DUPLICATE_RATIO, duplicateRatio + "");
    }
    if (minorTriggerFileCount != null) {
      updateProperties.set(SELF_OPTIMIZING_MINOR_TRIGGER_FILE_CNT, minorTriggerFileCount + "");
    }
    updateProperties.commit();
  }

  public static Builder builder(FusionTable table, int availableCore) {
    return new Builder(table, availableCore);
  }

  public void optimize() throws Exception {
    AbstractOptimizingPlanner planner = planner();
    List<RewriteStageTask> taskDescriptors = planner.planTasks();
    if (CollectionUtils.isEmpty(taskDescriptors)) {
      check(taskDescriptors, planner, null);
      return;
    }
    List<TaskRuntime<RewriteStageTask>> taskRuntimes = mockTaskRuntime(taskDescriptors);

    asyncExecute(taskRuntimes);

    IcebergTableCommit committer =
        committer(
            taskRuntimes,
            planner.getFromSequence(),
            planner.getToSequence(),
            planner.getTargetSnapshotId());
    committer.commit();
    check(taskDescriptors, planner, null);
  }

  public List<Checker> unTriggerChecker() {
    return checkers.stream().filter(s -> !s.senseHasChecked()).collect(Collectors.toList());
  }

  private void asyncExecute(List<TaskRuntime<RewriteStageTask>> taskRuntimes)
      throws InterruptedException, ExecutionException {
    CompletableFuture.allOf(
            taskRuntimes.stream()
                .map(
                    taskRuntime -> {
                      OptimizingExecutor<RewriteFilesOutput> optimizingExecutor =
                          optimizingExecutor(taskRuntime);
                      return CompletableFuture.supplyAsync(
                              optimizingExecutor::execute, executorPool)
                          .thenAccept(
                              s ->
                                  Mockito.when(taskRuntime.getTaskDescriptor().getOutput())
                                      .thenReturn(s));
                    })
                .toArray(CompletableFuture[]::new))
        .get();
  }

  private void check(
      List<RewriteStageTask> taskDescriptors,
      AbstractOptimizingPlanner planner,
      IcebergTableCommit commit)
      throws Exception {
    for (Checker checker : checkers) {
      if (checker.condition(table, taskDescriptors, planner, commit)) {
        checker.check(table, taskDescriptors, planner, commit);
      }
    }
  }

  private List<TaskRuntime<RewriteStageTask>> mockTaskRuntime(
      List<RewriteStageTask> taskDescriptors) {
    List<TaskRuntime<RewriteStageTask>> list = new ArrayList<>();
    for (RewriteStageTask taskDescriptor : taskDescriptors) {
      TaskRuntime<RewriteStageTask> taskRuntime = Mockito.mock(TaskRuntime.class);
      RewriteStageTask task = Mockito.mock(RewriteStageTask.class);
      Mockito.when(taskRuntime.getTaskDescriptor()).thenReturn(task);
      Mockito.when(taskRuntime.getTaskDescriptor().getPartition())
          .thenReturn(taskDescriptor.getPartition());
      Mockito.when(taskRuntime.getTaskDescriptor().getInput())
          .thenReturn(taskDescriptor.getInput());
      Mockito.when(taskRuntime.getProperties()).thenReturn(taskDescriptor.getProperties());
      Mockito.when(taskRuntime.getStatus()).thenReturn(TaskRuntime.Status.SUCCESS);
      list.add(taskRuntime);
    }
    return list;
  }

  private AbstractOptimizingPlanner planner() {
    table.refresh();
    DefaultTableRuntime tableRuntime = Mockito.mock(DefaultTableRuntime.class);

    Mockito.when(tableRuntime.getCurrentSnapshotId()).thenAnswer(f -> getCurrentSnapshotId());
    Mockito.when(tableRuntime.getCurrentChangeSnapshotId())
        .thenAnswer(f -> getCurrentChangeSnapshotId());
    Mockito.when(tableRuntime.getProcessId()).thenReturn(1L);
    Mockito.when(tableRuntime.getPendingInput()).thenReturn(null);
    Mockito.when(tableRuntime.getLastMinorOptimizingTime()).thenReturn(0L);
    Mockito.when(tableRuntime.getLastMajorOptimizingTime()).thenReturn(0L);
    Mockito.when(tableRuntime.getLastFullOptimizingTime()).thenReturn(0L);
    Mockito.when(tableRuntime.getOptimizingConfig()).thenAnswer(f -> optimizingConfig());
    Mockito.when(tableRuntime.getTableIdentifier())
        .thenReturn(ServerTableIdentifier.of(1L, "a", "b", "c", fusionTable.format()));
    return IcebergTableUtil.createOptimizingPlanner(
        tableRuntime,
        fusionTable,
        availableCore,
        OptimizerProperties.MAX_INPUT_FILE_SIZE_PER_THREAD_DEFAULT,
        tableRuntime.getProcessId());
  }

  private OptimizingConfig optimizingConfig() {
    TableConfiguration tableConfiguration =
        TableConfigurations.parseTableConfig(fusionTable.properties());
    return tableConfiguration.getOptimizingConfig();
  }

  private OptimizingExecutor<RewriteFilesOutput> optimizingExecutor(
      TaskRuntime<RewriteStageTask> taskRuntime) {
    return new IcebergRewriteExecutor(
        taskRuntime.getTaskDescriptor().getInput(), taskRuntime.getProperties());
  }

  private IcebergTableCommit committer(
      List<TaskRuntime<RewriteStageTask>> taskRuntimes,
      Map<String, Long> fromSequence,
      Map<String, Long> toSequence,
      Long formSnapshotId) {

    return new IcebergTableCommit(formSnapshotId, fusionTable, taskRuntimes);
  }

  private long getCurrentSnapshotId() {
    return IcebergTableUtil.getSnapshotId(table, false);
  }

  private long getCurrentChangeSnapshotId() {
    return Constants.INVALID_SNAPSHOT_ID;
  }

  private StructLikeMap<Long> getStructLike(Map<String, Long> partitionSequence) {
    PartitionSpec spec = table.spec();
    StructLikeMap<Long> results = StructLikeMap.create(spec.partitionType());
    partitionSequence.forEach(
        (partition, sequence) -> {
          if (spec.isUnpartitioned()) {
            results.put(TablePropertyUtil.EMPTY_STRUCT, sequence);
          } else {
            StructLike partitionData = PartitionDataFiles.data(spec, partition);
            results.put(partitionData, sequence);
          }
        });
    return results;
  }

  public interface Checker {

    boolean condition(
        Table table,
        @Nullable List<RewriteStageTask> latestTaskDescriptors,
        AbstractOptimizingPlanner latestPlanner,
        @Nullable IcebergTableCommit latestCommit);

    boolean senseHasChecked();

    void check(
        Table table,
        @Nullable List<RewriteStageTask> latestTaskDescriptors,
        AbstractOptimizingPlanner latestPlanner,
        @Nullable IcebergTableCommit latestCommit)
        throws Exception;
  }

  public static final class Builder {
    private final FusionTable table;
    private final int availableCore;
    private Long targetSize;
    private Integer fragmentRatio;
    private Double duplicateRatio;
    private Integer minorTriggerFileCount;

    private final List<Checker> checkers = new ArrayList<>();

    public Builder(FusionTable table, int availableCore) {
      this.table = table;
      this.availableCore = availableCore;
    }

    public Builder setTargetSize(Long targetSize) {
      this.targetSize = targetSize;
      return this;
    }

    public Builder setFragmentRatio(Integer fragmentRatio) {
      this.fragmentRatio = fragmentRatio;
      return this;
    }

    public Builder setDuplicateRatio(Double duplicateRatio) {
      this.duplicateRatio = duplicateRatio;
      return this;
    }

    public Builder setMinorTriggerFileCount(Integer minorTriggerFileCount) {
      this.minorTriggerFileCount = minorTriggerFileCount;
      return this;
    }

    public Builder addChecker(Checker checker) {
      checkers.add(checker);
      return this;
    }

    public CompleteOptimizingFlow build() {
      return new CompleteOptimizingFlow(
          table,
          availableCore,
          targetSize,
          fragmentRatio,
          duplicateRatio,
          minorTriggerFileCount,
          checkers);
    }
  }
}
