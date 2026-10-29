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

package io.olake.fusion.server.dashboard.utils;

import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.config.OptimizingConfig;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.optimizing.MetricsSummary;
import io.olake.fusion.server.FusionServiceConstants;
import io.olake.fusion.server.dashboard.model.TableOptimizingInfo;
import io.olake.fusion.server.optimizing.OptimizingStatus;
import io.olake.fusion.server.optimizing.OptimizingTaskMeta;
import io.olake.fusion.server.optimizing.TaskRuntime;
import io.olake.fusion.server.optimizing.TaskRuntime.Status;
import io.olake.fusion.server.persistence.TableRuntimeMeta;
import io.olake.fusion.server.table.TableConfigurations;
import io.olake.fusion.table.TableSummary;
import io.olake.fusion.table.descriptor.FilesStatistics;
import org.apache.amoro.shade.guava32.com.google.common.annotations.VisibleForTesting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

public class OptimizingUtil {

  /**
   * Build current table optimize info.
   *
   * @return TableOptimizeInfo
   */
  public static TableOptimizingInfo buildTableOptimizeInfo(
      ServerTableIdentifier identifier,
      TableRuntimeMeta tableRuntimeMeta,
      List<OptimizingTaskMeta> processTasks,
      List<TaskRuntime.TaskQuota> quotas,
      int threadCount) {
    TableOptimizingInfo tableOptimizeInfo = new TableOptimizingInfo(identifier);
    OptimizingStatus optimizingStatus = OptimizingStatus.ofCode(tableRuntimeMeta.getStatusCode());
    String displayStatus = optimizingStatus == null ? "UNKNOWN" : optimizingStatus.displayValue();
    tableOptimizeInfo.setOptimizeStatus(displayStatus);
    tableOptimizeInfo.setDuration(
        System.currentTimeMillis() - tableRuntimeMeta.getStatusCodeUpdateTime());
    TableConfiguration tableConfig =
        TableConfigurations.parseTableConfig(tableRuntimeMeta.getTableConfig());
    OptimizingConfig optimizingConfig = tableConfig.getOptimizingConfig();
    double targetQuota = optimizingConfig.getTargetQuota();
    tableOptimizeInfo.setQuota(
        targetQuota > 1 ? (int) targetQuota : (int) Math.ceil(targetQuota * threadCount));

    long endTime = System.currentTimeMillis();
    long startTime = System.currentTimeMillis() - FusionServiceConstants.QUOTA_LOOK_BACK_TIME;
    long quotaOccupy = calculateQuotaOccupy(processTasks, quotas, startTime, endTime);
    double quotaOccupation =
        (double) quotaOccupy
            / (FusionServiceConstants.QUOTA_LOOK_BACK_TIME * tableOptimizeInfo.getQuota());
    tableOptimizeInfo.setQuotaOccupation(
        BigDecimal.valueOf(quotaOccupation).setScale(4, RoundingMode.HALF_UP).doubleValue());

    FilesStatistics optimizeFileInfo;
    if (optimizingStatus != null && optimizingStatus.isProcessing()) {
      MetricsSummary summary = null;
      if (processTasks != null && !processTasks.isEmpty()) {
        List<MetricsSummary> taskSummary =
            processTasks.stream()
                .map(OptimizingTaskMeta::getMetricsSummary)
                .collect(Collectors.toList());
        summary = new MetricsSummary(taskSummary);
      }
      optimizeFileInfo = collectOptimizingFileInfo(summary);
    } else if (optimizingStatus == OptimizingStatus.PENDING) {
      optimizeFileInfo = collectPendingFileInfo(tableRuntimeMeta.getTableSummary());
    } else {
      optimizeFileInfo = null;
    }
    if (optimizeFileInfo != null) {
      tableOptimizeInfo.setFileCount(optimizeFileInfo.getFileCnt());
      tableOptimizeInfo.setFileSize(optimizeFileInfo.getTotalSize());
    }
    tableOptimizeInfo.setGroupName(tableRuntimeMeta.getGroupName());
    return tableOptimizeInfo;
  }

  @VisibleForTesting
  static long calculateQuotaOccupy(
      List<OptimizingTaskMeta> processTasks,
      List<TaskRuntime.TaskQuota> quotas,
      long startTime,
      long endTime) {
    long finishedOccupy = 0;
    if (quotas != null) {
      quotas.removeIf(task -> task.checkExpired(startTime));
      finishedOccupy =
          quotas.stream().mapToLong(taskQuota -> taskQuota.getQuotaTime(startTime)).sum();
    }
    long runningOccupy = 0;
    if (processTasks != null) {
      runningOccupy =
          processTasks.stream()
              .filter(
                  t ->
                      t.getStatus() != TaskRuntime.Status.CANCELED
                          && t.getStatus() != Status.SUCCESS
                          && t.getStatus() != TaskRuntime.Status.FAILED)
              .mapToLong(
                  task ->
                      TaskRuntime.taskRunningQuotaTime(
                          startTime, endTime, task.getStartTime(), task.getCostTime()))
              .sum();
    }
    return finishedOccupy + runningOccupy;
  }

  private static FilesStatistics collectPendingFileInfo(TableSummary tableSummary) {
    if (tableSummary == null) {
      return null;
    }
    return FilesStatistics.builder()
        .addFiles(tableSummary.getPendingFileSize(), tableSummary.getPendingFileCount())
        .build();
  }

  private static FilesStatistics collectOptimizingFileInfo(MetricsSummary metricsSummary) {
    if (metricsSummary == null) {
      return null;
    }
    return FilesStatistics.builder()
        .addFiles(metricsSummary.getEqualityDeleteSize(), metricsSummary.getEqDeleteFileCnt())
        .addFiles(metricsSummary.getPositionDeleteSize(), metricsSummary.getPosDeleteFileCnt())
        .addFiles(metricsSummary.getRewriteDataSize(), metricsSummary.getRewriteDataFileCnt())
        .build();
  }
}
