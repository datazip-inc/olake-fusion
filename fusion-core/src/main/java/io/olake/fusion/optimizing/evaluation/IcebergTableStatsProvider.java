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

import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.apache.iceberg.util.PropertyUtil;

import java.util.Map;

public class IcebergTableStatsProvider extends TableStatsProvider {

  public static final IcebergTableStatsProvider INSTANCE = new IcebergTableStatsProvider();

  private IcebergTableStatsProvider() {}

  @Override
  public BasicFileStats collect(Table table) {
    BasicFileStats stats = new BasicFileStats();
    acceptSnapshotIfPresent(stats, table.currentSnapshot());
    return stats;
  }

  private void acceptSnapshotIfPresent(BasicFileStats stats, Snapshot snapshot) {
    if (snapshot != null) {
      stats.accept(snapshot.summary());
    }
  }

  public static class BasicFileStats extends TableStatsProvider.BasicFileStats {
    public void accept(Map<String, String> summary) {
      deleteFileCnt +=
          PropertyUtil.propertyAsInt(summary, SnapshotSummary.TOTAL_DELETE_FILES_PROP, 0);
      dataFileCnt += PropertyUtil.propertyAsInt(summary, SnapshotSummary.TOTAL_DATA_FILES_PROP, 0);
      totalFileSize +=
          PropertyUtil.propertyAsLong(summary, SnapshotSummary.TOTAL_FILE_SIZE_PROP, 0);
    }
  }
}
