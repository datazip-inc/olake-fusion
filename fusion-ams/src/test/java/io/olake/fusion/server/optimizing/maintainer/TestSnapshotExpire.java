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

package io.olake.fusion.server.optimizing.maintainer;

import static io.olake.fusion.formats.iceberg.maintainer.IcebergTableMaintainer.FLINK_MAX_COMMITTED_CHECKPOINT_ID;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.formats.iceberg.maintainer.IcebergTableMaintainer;
import io.olake.fusion.maintainer.OptimizingInfo;
import io.olake.fusion.server.scheduler.inline.ExecutorTestBase;
import io.olake.fusion.server.table.TableConfigurations;
import io.olake.fusion.table.TableProperties;
import io.olake.fusion.utils.StatisticsFileUtil;
import org.apache.amoro.shade.guava32.com.google.common.collect.Iterables;
import org.apache.amoro.shade.guava32.com.google.common.collect.Iterators;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.util.StructLikeMap;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

@RunWith(Parameterized.class)
public class TestSnapshotExpire extends ExecutorTestBase {

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)},
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, false)}
    };
  }

  public TestSnapshotExpire(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  @Test
  public void testNotExpireFlinkLatestCommit4All() {
    Table table = getTable();
    writeAndCommitBaseStore(table);

    AppendFiles appendFiles = table.newAppend();
    appendFiles.set(FLINK_MAX_COMMITTED_CHECKPOINT_ID, "100");
    appendFiles.commit();

    AppendFiles appendFiles2 = table.newAppend();
    appendFiles2.set(FLINK_MAX_COMMITTED_CHECKPOINT_ID, "101");
    appendFiles2.commit();
    Snapshot checkpointTime2Snapshot = table.currentSnapshot();

    writeAndCommitBaseStore(table);
    Snapshot lastSnapshot = table.currentSnapshot();

    table.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "0").commit();

    Assert.assertEquals(4, Iterables.size(table.snapshots()));

    IcebergTableMaintainer tableMaintainer =
        new IcebergTableMaintainer(
            table,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(table));
    tableMaintainer.expireSnapshots();

    Assert.assertEquals(2, Iterables.size(table.snapshots()));
    List<Snapshot> expectedSnapshots = new ArrayList<>();
    expectedSnapshots.add(checkpointTime2Snapshot);
    expectedSnapshots.add(lastSnapshot);
    Assert.assertTrue(
        Iterators.elementsEqual(expectedSnapshots.iterator(), table.snapshots().iterator()));
  }

  @Test
  public void testNotExpireOptimizeCommit4All() {
    Table table = getTable();
    table.newAppend().commit();
    table.newAppend().commit();
    table.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "0").commit();

    TestTableMaintainerContext.Impl context =
        new TestTableMaintainerContext.Impl(
            TestTableMaintainerContext.parseTestTableConfig(table.properties()));
    new IcebergTableMaintainer(table, getFusionTable().io(), getFusionTable().id(), context)
        .expireSnapshots();
    Assert.assertEquals(1, Iterables.size(table.snapshots()));

    table.newAppend().commit();

    // mock optimizing task not committed
    long optimizeSnapshotId = table.currentSnapshot().snapshotId();
    OptimizingInfo optimizingInfo =
        new OptimizingInfo() {
          @Override
          public boolean isProcessing() {
            return true;
          }

          @Override
          public long getTargetSnapshotId() {
            return optimizeSnapshotId;
          }
        };
    context.setOptimizingInfo(optimizingInfo);
    List<Snapshot> expectedSnapshots = new ArrayList<>();
    expectedSnapshots.add(table.currentSnapshot());

    table.newAppend().commit();
    expectedSnapshots.add(table.currentSnapshot());
    table.newAppend().commit();
    expectedSnapshots.add(table.currentSnapshot());

    new IcebergTableMaintainer(table, getFusionTable().io(), getFusionTable().id(), context)
        .expireSnapshots();
    Assert.assertEquals(3, Iterables.size(table.snapshots()));
    Assert.assertTrue(
        Iterators.elementsEqual(expectedSnapshots.iterator(), table.snapshots().iterator()));
  }

  @Test
  public void testExpireTableFiles4All() {
    Table table = getTable();
    table.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "0").commit();
    List<DataFile> dataFiles = writeAndCommitBaseStore(table);

    DeleteFiles deleteFiles = table.newDelete();
    for (DataFile dataFile : dataFiles) {
      Assert.assertTrue(
          table.io().newInputFile(String.valueOf(dataFile.path().toString())).exists());
      deleteFiles.deleteFile(dataFile);
    }
    deleteFiles.commit();

    List<DataFile> newDataFiles = writeAndCommitBaseStore(table);
    Assert.assertEquals(3, Iterables.size(table.snapshots()));
    new IcebergTableMaintainer(
            table,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()))
        .expireSnapshots(System.currentTimeMillis(), 1);
    Assert.assertEquals(1, Iterables.size(table.snapshots()));

    dataFiles.forEach(
        file ->
            Assert.assertFalse(
                table.io().newInputFile(String.valueOf(file.path().toString())).exists()));
    newDataFiles.forEach(
        file ->
            Assert.assertTrue(
                table.io().newInputFile(String.valueOf(file.path().toString())).exists()));
  }

  @Test
  public void testExpireStatisticsFiles() {
    Table baseTable = getTable();
    baseTable.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "0").commit();
    // commit an empty snapshot and its statistic file
    baseTable.newAppend().commit();
    Snapshot s1 = baseTable.currentSnapshot();
    StatisticsFile file1 =
        StatisticsFileUtil.writerBuilder(baseTable)
            .withSnapshotId(s1.snapshotId())
            .build()
            .add(
                "optimized-sequence",
                StructLikeMap.create(baseTable.spec().partitionType()),
                StatisticsFileUtil.createPartitionDataSerializer(baseTable.spec(), Long.class))
            .complete();
    baseTable.updateStatistics().setStatistics(s1.snapshotId(), file1).commit();

    // commit an empty snapshot and its statistic file
    baseTable.newAppend().commit();
    Snapshot s2 = baseTable.currentSnapshot();
    StatisticsFile file2 =
        StatisticsFileUtil.writerBuilder(baseTable)
            .withSnapshotId(s2.snapshotId())
            .build()
            .add(
                "optimized-sequence",
                StructLikeMap.create(baseTable.spec().partitionType()),
                StatisticsFileUtil.createPartitionDataSerializer(baseTable.spec(), Long.class))
            .complete();
    baseTable.updateStatistics().setStatistics(s2.snapshotId(), file2).commit();

    long expireTime = waitUntilAfter(s2.timestampMillis());

    // commit an empty snapshot and its statistic file
    baseTable.newAppend().commit();
    Snapshot s3 = baseTable.currentSnapshot();
    // note: s2 ans s3 use the same statistics file
    StatisticsFile file3 = StatisticsFileUtil.copyToSnapshot(file2, s3.snapshotId());
    baseTable.updateStatistics().setStatistics(s3.snapshotId(), file3).commit();

    Assert.assertEquals(3, Iterables.size(baseTable.snapshots()));
    Assert.assertTrue(baseTable.io().newInputFile(String.valueOf(file1.path())).exists());
    Assert.assertTrue(baseTable.io().newInputFile(String.valueOf(file2.path())).exists());
    Assert.assertTrue(baseTable.io().newInputFile(String.valueOf(file3.path())).exists());
    new IcebergTableMaintainer(
            baseTable,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(baseTable))
        .expireSnapshots(expireTime, 1);

    Assert.assertEquals(1, Iterables.size(baseTable.snapshots()));
    Assert.assertFalse(baseTable.io().newInputFile(String.valueOf(file1.path())).exists());
    // file2 should not be removed, since it is used by s3
    Assert.assertTrue(baseTable.io().newInputFile(String.valueOf(file2.path())).exists());
    Assert.assertTrue(baseTable.io().newInputFile(String.valueOf(file3.path())).exists());
  }

  @Test
  public void testBaseTableGcDisabled() {
    Table testUnkeyedTable = getTable();
    testUnkeyedTable.updateProperties().set("gc.enabled", "false").commit();

    testUnkeyedTable.newAppend().commit();
    testUnkeyedTable.newAppend().commit();

    Assert.assertEquals(2, Iterables.size(testUnkeyedTable.snapshots()));

    IcebergTableMaintainer tableMaintainer =
        new IcebergTableMaintainer(
            testUnkeyedTable,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(testUnkeyedTable));
    testUnkeyedTable.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "0").commit();

    tableMaintainer.expireSnapshots();
    Assert.assertEquals(2, Iterables.size(testUnkeyedTable.snapshots()));

    testUnkeyedTable.updateProperties().set("gc.enabled", "true").commit();
    tableMaintainer =
        new IcebergTableMaintainer(
            testUnkeyedTable,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(testUnkeyedTable));
    tableMaintainer.expireSnapshots();
    Assert.assertEquals(1, Iterables.size(testUnkeyedTable.snapshots()));
  }

  @Test
  public void testRetainMinSnapshot() {
    Table table = getTable();
    table.newAppend().commit();
    table.newAppend().commit();
    List<Snapshot> expectedSnapshots = new ArrayList<>();
    expectedSnapshots.add(table.currentSnapshot());

    table.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "0s").commit();
    table.updateProperties().set(TableProperties.SNAPSHOT_MIN_COUNT, "3").commit();

    new IcebergTableMaintainer(
            table,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(table))
        .expireSnapshots();
    Assert.assertEquals(2, Iterables.size(table.snapshots()));

    table.newAppend().commit();
    expectedSnapshots.add(table.currentSnapshot());
    table.newAppend().commit();
    expectedSnapshots.add(table.currentSnapshot());

    new IcebergTableMaintainer(
            table,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(table))
        .expireSnapshots();
    Assert.assertEquals(3, Iterables.size(table.snapshots()));
    Assert.assertTrue(
        Iterators.elementsEqual(expectedSnapshots.iterator(), table.snapshots().iterator()));
  }

  @Test
  public void testSnapshotExpireConfig() {
    Table table = getTable();
    table.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "180s").commit();
    Assert.assertEquals(
        3L, TableConfigurations.parseTableConfig(table.properties()).getSnapshotTTLMinutes());

    // using default time unit: minutes
    table.updateProperties().set(TableProperties.SNAPSHOT_KEEP_DURATION, "720").commit();
    Assert.assertEquals(
        720L, TableConfigurations.parseTableConfig(table.properties()).getSnapshotTTLMinutes());

    table.updateProperties().set(TableProperties.SNAPSHOT_MIN_COUNT, "10").commit();
    Assert.assertEquals(
        10, TableConfigurations.parseTableConfig(table.properties()).getSnapshotMinCount());
  }

  private long waitUntilAfter(long timestampMillis) {
    long current = System.currentTimeMillis();
    while (current <= timestampMillis) {
      current = System.currentTimeMillis();
    }
    return current;
  }
}
