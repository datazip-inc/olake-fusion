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

package io.olake.fusion.formats.iceberg.maintainer;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.TableFormat;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.TableTestBase;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.maintainer.MaintainerMetrics;
import io.olake.fusion.maintainer.OptimizingInfo;
import io.olake.fusion.maintainer.TableMaintainerContext;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;

/** Snapshot expiry on a real local Iceberg table. */
public class TestIcebergTableMaintainerExpireSnapshots extends TableTestBase {

  public TestIcebergTableMaintainerExpireSnapshots() {
    super(new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, false));
  }

  @Test
  public void singleSnapshotIsKept() {
    Table table = getTable();
    Snapshot only = append(table);

    SnapshotExpirySummary summary =
        maintainer(table).expireSnapshotsWithSummary(only.timestampMillis() + 1, 1);

    Assert.assertEquals(0, summary.snapshotsExpired());
    table.refresh();
    Assert.assertNotNull(table.snapshot(only.snapshotId()));
  }

  @Test
  public void taggedSnapshotIsKept() {
    Table table = getTable();
    Snapshot tagged = append(table);
    for (int i = 0; i < 4; i++) {
      append(table);
    }
    table.manageSnapshots().createTag("keep-me", tagged.snapshotId()).commit();
    long afterLast = table.currentSnapshot().timestampMillis() + 1;

    SnapshotExpirySummary summary = maintainer(table).expireSnapshotsWithSummary(afterLast, 1);

    table.refresh();
    Assert.assertNotNull(table.snapshot(tagged.snapshotId()));
    Assert.assertEquals(2, summary.snapshotsRetained());
    Assert.assertEquals(3, summary.snapshotsExpired());
  }

  private static Snapshot append(Table table) {
    DataFile file =
        DataFiles.builder(table.spec())
            .withPath(table.location() + "/data/" + UUID.randomUUID() + ".parquet")
            .withFileSizeInBytes(10)
            .withRecordCount(1)
            .build();
    table.newAppend().appendFile(file).commit();
    return table.currentSnapshot();
  }

  private IcebergTableMaintainer maintainer(Table table) {
    return new IcebergTableMaintainer(
        table, getFusionTable().io(), getFusionTable().id(), new NoOptimizingContext());
  }

  /** A table with no compaction running and default configuration. */
  private static class NoOptimizingContext implements TableMaintainerContext {
    @Override
    public TableConfiguration getTableConfiguration() {
      return new TableConfiguration();
    }

    @Override
    public MaintainerMetrics getMetrics() {
      return null;
    }

    @Override
    public OptimizingInfo getOptimizingInfo() {
      return OptimizingInfo.EMPTY;
    }
  }
}
