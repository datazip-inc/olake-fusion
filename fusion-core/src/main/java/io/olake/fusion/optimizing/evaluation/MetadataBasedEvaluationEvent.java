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

package io.olake.fusion.optimizing.evaluation;

import io.olake.fusion.config.OptimizingConfig;
import org.apache.iceberg.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetadataBasedEvaluationEvent {
  private static final Logger logger = LoggerFactory.getLogger(MetadataBasedEvaluationEvent.class);

  public static boolean isReachFallbackInterval(OptimizingConfig config, long lastPlanTime) {
    long fallbackInterval = config.getEvaluationFallbackInterval();
    return fallbackInterval >= 0 && System.currentTimeMillis() - lastPlanTime >= fallbackInterval;
  }

  public static boolean isEvaluatingNecessary(
      OptimizingConfig config, Table table, long lastPlanTime) {
    // Step 1: Perform periodic scheduling according to the fallback interval to avoid false
    // positives or
    // missed triggers based on metadata metric-driven evaluation
    if (isReachFallbackInterval(config, lastPlanTime)) {
      logger.debug("Maximum interval for evaluating table {} has reached.", table.name());
      return true;
    }

    TableStatsProvider.BasicFileStats basicStats = getTableStats(table);
    // Currently only supports ICEBERG.
    // For other formats this will return null.
    if (basicStats != null) {
      // Step 2: Empty table should skip evaluating pending input
      //    TableStatsProvider provider = getTableStatsProvider(table);
      if (basicStats.dataFileCnt == 0) {
        logger.debug(
            "Table {} contains no data files, skip evaluating pending input.", table.name());
        return false;
      }

      // Step 3: If the condition `delete file=0 && avg file size > target size * ratio` is
      // satisfied,
      // then evaluating the pending input is considered unnecessary and will be skipped.
      long minTargetSize = (long) (config.getTargetSize() * config.getMinTargetSizeRatio());
      double avgFileSize =
          basicStats.dataFileCnt > 0
              ? (double) basicStats.totalFileSize / basicStats.dataFileCnt
              : 0;

      if (basicStats.deleteFileCnt == 0 && avgFileSize > minTargetSize) {
        logger.debug(
            "Table {} contains only appended data and no deleted files (average file size: {}), skip evaluating pending input.",
            table.name(),
            avgFileSize);
        return false;
      }
    }
    return true;
  }

  private static TableStatsProvider.BasicFileStats getTableStats(Table table) {
    return IcebergTableStatsProvider.INSTANCE.collect(table);
  }

  public static boolean isPartitionPendingNecessary(
      OptimizingConfig config, long partitionSquaredErrorSum, long partitionFileCount) {
    long mseTolerance = config.getEvaluationMseTolerance();
    return partitionFileCount > 1
        && (mseTolerance == 0
            || (double) partitionSquaredErrorSum / partitionFileCount
                >= (double) mseTolerance * mseTolerance);
  }
}
