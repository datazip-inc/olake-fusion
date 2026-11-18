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
import io.olake.fusion.config.DataExpirationConfig;
import io.olake.fusion.config.OptimizingConfig;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.table.TableProperties;
import io.olake.fusion.utils.CompatiblePropertyUtil;
import io.olake.fusion.utils.PropertyUtil;
import org.apache.amoro.shade.guava32.com.google.common.annotations.VisibleForTesting;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.primitives.Ints;
import org.apache.commons.lang3.StringUtils;

import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/** Util class to help create table configuration for Iceberg tables. */
public class TableConfigurations {

  /**
   * In olake go pebble index is being used, so if we not have more then 3, there are chances of
   * invalidating the index.
   */
  /** The lowest {@code snapshot.keep.min-count} snapshot expiry accepts. */
  public static final int SNAPSHOT_MIN_COUNT_LOWEST = 10;

  /**
   * Parse table configuration from table properties
   *
   * @param properties table properties
   * @return table configuration
   */
  public static TableConfiguration parseTableConfig(Map<String, String> properties) {
    boolean gcEnabled = CompatiblePropertyUtil.propertyAsBoolean(properties, "gc.enabled", true);
    String keepDuration =
        CompatiblePropertyUtil.propertyAsString(
            properties,
            TableProperties.SNAPSHOT_KEEP_DURATION,
            TableProperties.SNAPSHOT_KEEP_DURATION_DEFAULT);
    String minCount =
        CompatiblePropertyUtil.propertyAsString(
            properties,
            TableProperties.SNAPSHOT_MIN_COUNT,
            String.valueOf(TableProperties.SNAPSHOT_MIN_COUNT_DEFAULT));
    Long snapshotTTLMinutes = parseSnapshotTTLMinutes(keepDuration);
    Integer snapshotMinCount = Ints.tryParse(minCount.trim());
    String snapshotExpireCron =
        CompatiblePropertyUtil.propertyAsString(
            properties, TableProperties.SNAPSHOT_EXPIRE_TRIGGER_CRON, null);
    // Setting a cron turns expiry on unless snapshot-expire.enabled says otherwise.
    boolean snapshotExpireCronSet =
        snapshotExpireCron != null && !snapshotExpireCron.trim().isEmpty();
    return new TableConfiguration()
        .setExpireSnapshotEnabled(
            gcEnabled
                && CompatiblePropertyUtil.propertyAsBoolean(
                    properties, TableProperties.SNAPSHOT_EXPIRE_ENABLED, snapshotExpireCronSet))
        .setSnapshotExpireTriggerCron(snapshotExpireCron)
        .setSnapshotExpireConfigError(
            snapshotExpireConfigError(keepDuration, snapshotTTLMinutes, minCount, snapshotMinCount))
        .setSnapshotTTLMinutes(
            snapshotTTLMinutes != null
                ? snapshotTTLMinutes
                : parseSnapshotTTLMinutes(TableProperties.SNAPSHOT_KEEP_DURATION_DEFAULT))
        .setSnapshotMinCount(
            snapshotMinCount != null
                ? snapshotMinCount
                : TableProperties.SNAPSHOT_MIN_COUNT_DEFAULT)
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
        .setOptimizingConfig(parseOptimizingConfig(properties))
        .setExpiringDataConfig(parseDataExpirationConfig(properties));
  }

  /**
   * Parse table data expiration config from table properties
   *
   * @param properties table properties
   * @return table expiration config
   */
  @VisibleForTesting
  public static DataExpirationConfig parseDataExpirationConfig(Map<String, String> properties) {
    boolean gcEnabled =
        CompatiblePropertyUtil.propertyAsBoolean(
            properties, org.apache.iceberg.TableProperties.GC_ENABLED, true);
    DataExpirationConfig config =
        new DataExpirationConfig()
            .setEnabled(
                gcEnabled
                    && CompatiblePropertyUtil.propertyAsBoolean(
                        properties,
                        TableProperties.ENABLE_DATA_EXPIRATION,
                        TableProperties.ENABLE_DATA_EXPIRATION_DEFAULT))
            .setExpirationLevel(
                DataExpirationConfig.ExpireLevel.fromString(
                    CompatiblePropertyUtil.propertyAsString(
                        properties,
                        TableProperties.DATA_EXPIRATION_LEVEL,
                        TableProperties.DATA_EXPIRATION_LEVEL_DEFAULT)))
            .setExpirationField(
                CompatiblePropertyUtil.propertyAsString(
                    properties, TableProperties.DATA_EXPIRATION_FIELD, null))
            .setDateTimePattern(
                CompatiblePropertyUtil.propertyAsString(
                    properties,
                    TableProperties.DATA_EXPIRATION_DATE_STRING_PATTERN,
                    TableProperties.DATA_EXPIRATION_DATE_STRING_PATTERN_DEFAULT))
            .setNumberDateFormat(
                CompatiblePropertyUtil.propertyAsString(
                    properties,
                    TableProperties.DATA_EXPIRATION_DATE_NUMBER_FORMAT,
                    TableProperties.DATA_EXPIRATION_DATE_NUMBER_FORMAT_DEFAULT))
            .setBaseOnRule(
                parseDataExpirationBaseOnRule(
                    CompatiblePropertyUtil.propertyAsString(
                        properties,
                        TableProperties.DATA_EXPIRATION_BASE_ON_RULE,
                        TableProperties.DATA_EXPIRATION_BASE_ON_RULE_DEFAULT)));
    String retention =
        CompatiblePropertyUtil.propertyAsString(
            properties, TableProperties.DATA_EXPIRATION_RETENTION_TIME, null);
    if (StringUtils.isNotBlank(retention)) {
      config.setRetentionTime(ConfigHelpers.TimeUtils.parseDuration(retention).toMillis());
    }

    return config;
  }

  private static DataExpirationConfig.BaseOnRule parseDataExpirationBaseOnRule(String since) {
    Preconditions.checkArgument(
        null != since, TableProperties.DATA_EXPIRATION_BASE_ON_RULE + " is invalid: null");
    try {
      return DataExpirationConfig.BaseOnRule.valueOf(since.toUpperCase(Locale.ENGLISH));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format("Unable to expire data since: %s", since), e);
    }
  }

  /** Minutes of {@code snapshot.keep.duration} (no unit means days), or null when unparsable. */
  private static Long parseSnapshotTTLMinutes(String keepDuration) {
    try {
      return ConfigHelpers.TimeUtils.parseDuration(keepDuration, ChronoUnit.DAYS).getSeconds() / 60;
    } catch (IllegalArgumentException | ArithmeticException e) {
      return null;
    }
  }

  /**
   * Why the snapshot expiry settings cannot be used, or null. A bad value never fails the table
   * configuration, so compaction keeps running; only the expiry run fails on it.
   */
  private static String snapshotExpireConfigError(
      String keepDuration, Long snapshotTTLMinutes, String minCount, Integer snapshotMinCount) {
    if (snapshotTTLMinutes == null || snapshotTTLMinutes < 1) {
      return invalidSetting(TableProperties.SNAPSHOT_KEEP_DURATION, keepDuration);
    }
    if (snapshotMinCount == null || snapshotMinCount < SNAPSHOT_MIN_COUNT_LOWEST) {
      return invalidSetting(TableProperties.SNAPSHOT_MIN_COUNT, minCount);
    }
    return null;
  }

  private static String invalidSetting(String property, String value) {
    return String.format("invalid %s '%s'", property, value);
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
