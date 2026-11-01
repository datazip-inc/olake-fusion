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

import io.olake.fusion.config.ConfigHelpers;
import io.olake.fusion.config.OptimizingConfig;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.table.TableProperties;
import io.olake.fusion.utils.CompatiblePropertyUtil;
import io.olake.fusion.utils.PropertyUtil;
import org.apache.amoro.shade.guava32.com.google.common.annotations.VisibleForTesting;

import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Util class to help create table configuration for Iceberg tables. */
public class TableConfigurations {

  /**
   * Parse table configuration from table properties
   *
   * @param properties table properties
   * @return table configuration
   */
  public static TableConfiguration parseTableConfig(Map<String, String> properties) {
    boolean gcEnabled = CompatiblePropertyUtil.propertyAsBoolean(properties, "gc.enabled", true);
    return new TableConfiguration()
        .setExpireSnapshotEnabled(
            gcEnabled
                && CompatiblePropertyUtil.propertyAsBoolean(
                    properties,
                    TableProperties.ENABLE_TABLE_EXPIRE,
                    TableProperties.ENABLE_TABLE_EXPIRE_DEFAULT))
        .setSnapshotTTLMinutes(
            ConfigHelpers.TimeUtils.parseDuration(
                        CompatiblePropertyUtil.propertyAsString(
                            properties,
                            TableProperties.SNAPSHOT_KEEP_DURATION,
                            TableProperties.SNAPSHOT_KEEP_DURATION_DEFAULT),
                        ChronoUnit.MINUTES)
                    .getSeconds()
                / 60)
        .setSnapshotMinCount(
            CompatiblePropertyUtil.propertyAsInt(
                properties,
                TableProperties.SNAPSHOT_MIN_COUNT,
                TableProperties.SNAPSHOT_MIN_COUNT_DEFAULT))
        .setFlinkCheckpointRetention(
            ConfigHelpers.TimeUtils.parseDuration(
                    CompatiblePropertyUtil.propertyAsString(
                        properties,
                        TableProperties.SNAPSHOT_FLINK_CHECKPOINT_RETENTION,
                        TableProperties.SNAPSHOT_FLINK_CHECKPOINT_RETENTION_DEFAULT))
                .toMillis())
        .setChangeDataTTLMinutes(
            CompatiblePropertyUtil.propertyAsLong(
                properties,
                TableProperties.CHANGE_DATA_TTL,
                TableProperties.CHANGE_DATA_TTL_DEFAULT))
        .setCleanOrphanEnabled(
            gcEnabled
                && CompatiblePropertyUtil.propertyAsBoolean(
                    properties,
                    TableProperties.ENABLE_ORPHAN_CLEAN,
                    TableProperties.ENABLE_ORPHAN_CLEAN_DEFAULT))
        .setOrphanExistingMinutes(
            CompatiblePropertyUtil.propertyAsLong(
                properties,
                TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME,
                TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME_DEFAULT))
        .setDeleteDanglingDeleteFilesEnabled(
            gcEnabled
                && CompatiblePropertyUtil.propertyAsBoolean(
                    properties,
                    TableProperties.ENABLE_DANGLING_DELETE_FILES_CLEAN,
                    TableProperties.ENABLE_DANGLING_DELETE_FILES_CLEAN_DEFAULT))
        .setOptimizingConfig(parseOptimizingConfig(properties));
  }

  /**
   * Parse optimizing config from table properties
   *
   * @param properties table properties
   * @return table optimizing config
   */
  @VisibleForTesting
  public static OptimizingConfig parseOptimizingConfig(Map<String, String> properties) {
    return new OptimizingConfig()
        .setEnabled(
            CompatiblePropertyUtil.propertyAsBoolean(
                properties,
                TableProperties.ENABLE_SELF_OPTIMIZING,
                TableProperties.ENABLE_SELF_OPTIMIZING_DEFAULT))
        .setAllowPartialCommit(
            CompatiblePropertyUtil.propertyAsBoolean(
                properties,
                TableProperties.SELF_OPTIMIZING_ALLOW_PARTIAL_COMMIT,
                TableProperties.SELF_OPTIMIZING_ALLOW_PARTIAL_COMMIT_DEFAULT))
        .setMaxExecuteRetryCount(
            CompatiblePropertyUtil.propertyAsInt(
                properties,
                TableProperties.SELF_OPTIMIZING_EXECUTE_RETRY_NUMBER,
                TableProperties.SELF_OPTIMIZING_EXECUTE_RETRY_NUMBER_DEFAULT))
        .setOptimizerGroup(
            CompatiblePropertyUtil.propertyAsString(
                properties,
                TableProperties.SELF_OPTIMIZING_GROUP,
                TableProperties.SELF_OPTIMIZING_GROUP_DEFAULT))
        .setFragmentRatio(
            CompatiblePropertyUtil.propertyAsInt(
                properties,
                TableProperties.SELF_OPTIMIZING_FRAGMENT_RATIO,
                TableProperties.SELF_OPTIMIZING_FRAGMENT_RATIO_DEFAULT))
        .setMinTargetSizeRatio(
            CompatiblePropertyUtil.propertyAsDouble(
                properties,
                TableProperties.SELF_OPTIMIZING_MIN_TARGET_SIZE_RATIO,
                TableProperties.SELF_OPTIMIZING_MIN_TARGET_SIZE_RATIO_DEFAULT))
        .setMaxFileCount(
            CompatiblePropertyUtil.propertyAsInt(
                properties,
                TableProperties.SELF_OPTIMIZING_MAX_FILE_CNT,
                TableProperties.SELF_OPTIMIZING_MAX_FILE_CNT_DEFAULT))
        .setOpenFileCost(
            CompatiblePropertyUtil.propertyAsLong(
                properties,
                TableProperties.SPLIT_OPEN_FILE_COST,
                TableProperties.SPLIT_OPEN_FILE_COST_DEFAULT))
        .setTargetSize(
            CompatiblePropertyUtil.propertyAsLong(
                properties,
                TableProperties.SELF_OPTIMIZING_TARGET_SIZE,
                TableProperties.SELF_OPTIMIZING_TARGET_SIZE_DEFAULT))
        .setMaxTaskSize(
            CompatiblePropertyUtil.propertyAsLong(
                properties,
                TableProperties.SELF_OPTIMIZING_MAX_TASK_SIZE,
                TableProperties.SELF_OPTIMIZING_MAX_TASK_SIZE_DEFAULT))
        .setTargetQuota(
            CompatiblePropertyUtil.propertyAsDouble(
                properties,
                TableProperties.SELF_OPTIMIZING_QUOTA,
                TableProperties.SELF_OPTIMIZING_QUOTA_DEFAULT))
        .setMinorLeastFileCount(
            CompatiblePropertyUtil.propertyAsInt(
                properties,
                TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_FILE_CNT,
                TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_FILE_CNT_DEFAULT))
        .setMajorDuplicateRatio(
            CompatiblePropertyUtil.propertyAsDouble(
                properties,
                TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_DUPLICATE_RATIO,
                TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_DUPLICATE_RATIO_DEFAULT))
        .setFullRewriteAllFiles(
            CompatiblePropertyUtil.propertyAsBoolean(
                properties,
                TableProperties.SELF_OPTIMIZING_FULL_REWRITE_ALL_FILES,
                TableProperties.SELF_OPTIMIZING_FULL_REWRITE_ALL_FILES_DEFAULT))
        .setFilter(
            CompatiblePropertyUtil.propertyAsString(
                properties,
                TableProperties.SELF_OPTIMIZING_FILTER,
                TableProperties.SELF_OPTIMIZING_FILTER_DEFAULT))
        .setBaseHashBucket(
            CompatiblePropertyUtil.propertyAsInt(
                properties,
                TableProperties.BASE_FILE_INDEX_HASH_BUCKET,
                TableProperties.BASE_FILE_INDEX_HASH_BUCKET_DEFAULT))
        .setBaseRefreshInterval(
            PropertyUtil.propertyAsLong(
                properties,
                TableProperties.BASE_REFRESH_INTERVAL,
                TableProperties.BASE_REFRESH_INTERVAL_DEFAULT))
        .setMinPlanInterval(
            PropertyUtil.propertyAsLong(
                properties,
                TableProperties.SELF_OPTIMIZING_MIN_PLAN_INTERVAL,
                TableProperties.SELF_OPTIMIZING_MIN_PLAN_INTERVAL_DEFAULT))
        .setEvaluationFallbackInterval(
            PropertyUtil.propertyAsLong(
                properties,
                TableProperties.SELF_OPTIMIZING_EVALUATION_FALLBACK_INTERVAL,
                TableProperties.SELF_OPTIMIZING_EVALUATION_FALLBACK_INTERVAL_DEFAULT))
        .setEvaluationMseTolerance(
            PropertyUtil.propertyAsLong(
                properties,
                TableProperties.SELF_OPTIMIZING_EVALUATION_FILE_SIZE_MSE_TOLERANCE,
                TableProperties.SELF_OPTIMIZING_EVALUATION_FILE_SIZE_MSE_TOLERANCE_DEFAULT))
        .setMinorTriggerCron(
            CompatiblePropertyUtil.propertyAsString(
                properties, TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON, null))
        .setMajorTriggerCron(
            CompatiblePropertyUtil.propertyAsString(
                properties, TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON, null))
        .setFullTriggerCron(
            CompatiblePropertyUtil.propertyAsString(
                properties, TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON, null));
  }
}
