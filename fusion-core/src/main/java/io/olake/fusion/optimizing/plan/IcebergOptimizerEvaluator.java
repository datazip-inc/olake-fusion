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

package io.olake.fusion.optimizing.plan;

import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.config.OptimizingConfig;
import io.olake.fusion.table.MixedTable;
import io.olake.fusion.table.TableSnapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.util.Pair;

public class IcebergOptimizerEvaluator extends AbstractOptimizingEvaluator {
  public IcebergOptimizerEvaluator(
      ServerTableIdentifier identifier,
      OptimizingConfig config,
      MixedTable table,
      TableSnapshot currentSnapshot,
      int maxPendingPartitions,
      long lastMinorOptimizingTime,
      long lastFullOptimizingTime,
      long lastMajorOptimizingTime) {
    super(
        identifier,
        config,
        table,
        currentSnapshot,
        maxPendingPartitions,
        lastMinorOptimizingTime,
        lastFullOptimizingTime,
        lastMajorOptimizingTime);
  }

  @Override
  protected PartitionEvaluator buildEvaluator(Pair<Integer, StructLike> partition) {
    return new CommonPartitionEvaluator(
        identifier,
        config,
        partition,
        System.currentTimeMillis(),
        lastMinorOptimizingTime,
        lastFullOptimizingTime,
        lastMajorOptimizingTime);
  }
}
