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

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.catalog.TableTestBase;
import io.olake.fusion.config.OptimizingConfig;
import io.olake.fusion.data.ChangeAction;
import io.olake.fusion.io.DataTestHelpers;
import io.olake.fusion.io.IcebergDataTestHelpers;
import io.olake.fusion.io.writer.RecordWithAction;
import io.olake.fusion.optimizing.plan.CommonPartitionEvaluator;
import io.olake.fusion.optimizing.plan.PartitionEvaluator;
import io.olake.fusion.optimizing.scan.IcebergTableFileScanHelper;
import io.olake.fusion.optimizing.scan.TableFileScanHelper;
import io.olake.fusion.table.TableProperties;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableList;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.Pair;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class TestMetadataBasedEvaluationEvent extends TableTestBase {

  public TestMetadataBasedEvaluationEvent(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  public static final Schema TABLE_SCHEMA =
      new Schema(
          Lists.newArrayList(
              Types.NestedField.required(1, "id", Types.IntegerType.get()),
              Types.NestedField.required(2, "name", Types.StringType.get()),
              Types.NestedField.required(3, "ts", Types.LongType.get()),
              Types.NestedField.required(4, "op_time", Types.TimestampType.withoutZone())),
          Sets.newHashSet(1, 2, 3, 4));
  public static final PartitionSpec SPEC =
      PartitionSpec.builderFor(TABLE_SCHEMA).day("op_time").build();

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {
        new BasicCatalogTestHelper(TableFormat.ICEBERG),
        new BasicTableTestHelper(TABLE_SCHEMA, true, SPEC)
      }
    };
  }

  public void initData() throws IOException {
    write(getTable(), initRecords(1, "aaa", 0, 1, ChangeAction.INSERT));
    write(getTable(), initRecords(2, "bbb", 0, 1, ChangeAction.INSERT));
  }

  private List<RecordWithAction> initRecords(
      int id, String name, long ts, int day, ChangeAction action) {
    ImmutableList.Builder<RecordWithAction> builder = ImmutableList.builder();
    builder.add(
        new RecordWithAction(
            DataTestHelpers.createRecord(id, name, ts, String.format("2022-01-%02dT12:00:00", day)),
            action));

    return builder.build();
  }

  private List<Record> initRecords(int from, int to, long ts, String opTime) {
    ImmutableList.Builder<Record> builder = ImmutableList.builder();
    for (int i = from; i <= to; i++) {
      builder.add(tableTestHelper().generateTestRecord(i, i + "", ts, opTime));
    }

    return builder.build();
  }

  private void write(Table table, List<RecordWithAction> list) throws IOException {
    WriteResult result = IcebergDataTestHelpers.delta(table, list);

    RowDelta rowDelta = table.newRowDelta();
    Arrays.stream(result.dataFiles()).forEach(rowDelta::addRows);
    Arrays.stream(result.deleteFiles()).forEach(rowDelta::addDeletes);
    rowDelta.commit();
  }

  public void addChangeStoreData() throws IOException {
    Table mixedTable = getTable();
    write(mixedTable, initRecords(1, "aaa", 0, 1, ChangeAction.DELETE));
    write(mixedTable, initRecords(2, "ccc", 0, 1, ChangeAction.UPDATE_AFTER));
  }

  @Test
  public void test_evaluating_metadataBasedTriggerEnabled() {
    OptimizingConfig config = getDefaultOptimizingConfig();
    Assert.assertFalse(config.isMetadataBasedTriggerEnabled());

    config.setEvaluationFallbackInterval(Long.MAX_VALUE);
    Assert.assertTrue(config.isMetadataBasedTriggerEnabled());
  }

  @Test
  public void test_evaluating_emptyTable() {
    // Temporarily set self-optimizing.evaluation.fallback-interval to Long.MAX_VALUE to prevent
    // triggering due to reaching the fallback interval.
    OptimizingConfig config =
        getDefaultOptimizingConfig().setEvaluationFallbackInterval(Long.MAX_VALUE);
    Table table = getTable();

    // Verify the empty table stats
    TableStatsProvider.BasicFileStats stats = IcebergTableStatsProvider.INSTANCE.collect(table);
    Assert.assertEquals(0, stats.dataFileCnt);
    Assert.assertEquals(0, stats.totalFileSize);
    Assert.assertEquals(0, stats.deleteFileCnt);

    // Verify the empty table evaluation
    Assert.assertTrue(config.isMetadataBasedTriggerEnabled());
    Assert.assertFalse(MetadataBasedEvaluationEvent.isReachFallbackInterval(config, 0L));
    Assert.assertFalse(MetadataBasedEvaluationEvent.isEvaluatingNecessary(config, table, 0L));
  }

  @Test
  public void test_evaluating_nonEmptyTable() throws IOException {
    initData();
    OptimizingConfig config = getDefaultOptimizingConfig();
    Table table = getTable();

    // Verify the nonEmpty table stats
    TableStatsProvider.BasicFileStats stats = IcebergTableStatsProvider.INSTANCE.collect(table);
    Assert.assertTrue(stats.dataFileCnt > 0);
    Assert.assertTrue(stats.totalFileSize > 0);
    Assert.assertEquals(0, stats.deleteFileCnt);

    // 1. Test for metadata-based trigger disabled.
    config.setEvaluationFallbackInterval(-1);
    Assert.assertFalse(config.isMetadataBasedTriggerEnabled());

    // 2. Test for metadata-based trigger enabled.
    // Temporarily set self-optimizing.evaluation.fallback-interval to Long.MAX_VALUE to prevent
    // triggering due to reaching the fallback interval.
    config.setEvaluationFallbackInterval(Long.MAX_VALUE);
    Assert.assertTrue(config.isMetadataBasedTriggerEnabled());
    Assert.assertFalse(MetadataBasedEvaluationEvent.isReachFallbackInterval(config, 0L));

    // 2.1 Test for evaluating pendingInput necessary.
    config.setTargetSize(134217728);
    Assert.assertTrue(MetadataBasedEvaluationEvent.isEvaluatingNecessary(config, table, 0L));

    // 2.2 Test for evaluating pendingInput not necessary
    // Temporarily set self-optimizing.target-size to a lower value than the average file size
    config.setTargetSize(100);
    Assert.assertFalse(MetadataBasedEvaluationEvent.isEvaluatingNecessary(config, table, 0L));

    // 2.3 Test for evaluating pendingInput necessary because the fallback interval has been
    // reached.
    config.setEvaluationFallbackInterval(0L);
    Assert.assertTrue(config.isMetadataBasedTriggerEnabled());
    Assert.assertTrue(MetadataBasedEvaluationEvent.isEvaluatingNecessary(config, table, 0L));
  }

  @Test
  public void test_evaluating_pendingInput_nonEmptyTable() throws IOException {
    initData();
    // Set metadata-based trigger enabled and fallback interval not reached.
    // Use minorLeastFileCount=2 so the two small data files from initData() satisfy minor
    // scheduling (default is 12, so isMinorNecessary() would otherwise stay false and
    // isNecessary() would be false even when metadata-based pending checks pass).
    OptimizingConfig config =
        getDefaultOptimizingConfig()
            .setEvaluationFallbackInterval(Long.MAX_VALUE)
            .setMinorLeastFileCount(2);
    Table table = getTable();

    // 1. Test file size square error sum updates during partition plans initialization using
    // default mse tolerance (=0) , expecting no updates.
    TableFileScanHelper tableFileScanHelper = initTableFileScanHelper();
    Map<String, PartitionEvaluator> partitionPlanMap =
        initPartitionPlans(tableFileScanHelper, config);

    Assert.assertEquals(1, partitionPlanMap.size());

    long sizeSquaredErrorSum =
        ((CommonPartitionEvaluator) new ArrayList<>(partitionPlanMap.values()).get(0))
            .getFileSizeSquaredErrorSum();
    Assert.assertEquals(0L, sizeSquaredErrorSum);

    List<PartitionEvaluator> necessaryPartitions =
        partitionPlanMap.values().stream()
            .filter(PartitionEvaluator::isNecessary)
            .collect(Collectors.toList());
    Assert.assertEquals(1, necessaryPartitions.size());

    // 2. Set mse tolerance > 0 to enabled file size square error sum update during initializing
    // Partition plans.
    config.setEvaluationMseTolerance(120000000);
    partitionPlanMap = initPartitionPlans(tableFileScanHelper, config);
    Assert.assertEquals(1, partitionPlanMap.size());

    sizeSquaredErrorSum =
        ((CommonPartitionEvaluator) new ArrayList<>(partitionPlanMap.values()).get(0))
            .getFileSizeSquaredErrorSum();
    Assert.assertTrue(sizeSquaredErrorSum > 0);

    necessaryPartitions =
        partitionPlanMap.values().stream()
            .filter(PartitionEvaluator::isNecessary)
            .collect(Collectors.toList());
    Assert.assertEquals(0, necessaryPartitions.size());

    // 3. Test the file size variance updated after adding change data.
    addChangeStoreData();
    partitionPlanMap = initPartitionPlans(initTableFileScanHelper(), config);
    Assert.assertEquals(1, partitionPlanMap.size());

    long sizeSquaredErrorSumUpdated1 =
        ((CommonPartitionEvaluator) new ArrayList<>(partitionPlanMap.values()).get(0))
            .getFileSizeSquaredErrorSum();
    Assert.assertNotEquals(sizeSquaredErrorSum, sizeSquaredErrorSumUpdated1);

    necessaryPartitions =
        partitionPlanMap.values().stream()
            .filter(PartitionEvaluator::isNecessary)
            .collect(Collectors.toList());
    Assert.assertEquals(0, necessaryPartitions.size());

    // 4. Set mse tolerance smaller for partitions to test necessary pending.
    config.setEvaluationMseTolerance(100000000);
    partitionPlanMap = initPartitionPlans(initTableFileScanHelper(), config);

    long sizeSquaredErrorSumUpdated2 =
        ((CommonPartitionEvaluator) new ArrayList<>(partitionPlanMap.values()).get(0))
            .getFileSizeSquaredErrorSum();
    Assert.assertEquals(sizeSquaredErrorSumUpdated2, sizeSquaredErrorSumUpdated1);

    necessaryPartitions =
        partitionPlanMap.values().stream()
            .filter(PartitionEvaluator::isNecessary)
            .collect(Collectors.toList());
    Assert.assertEquals(1, necessaryPartitions.size());
  }

  private TableFileScanHelper initTableFileScanHelper() {
    Table mixedTable = getTable();
    TableFileScanHelper tableFileScanHelper =
        new IcebergTableFileScanHelper(mixedTable, mixedTable.currentSnapshot().snapshotId());
    tableFileScanHelper.withPartitionFilter(Expressions.alwaysTrue());

    return tableFileScanHelper;
  }

  private Map<String, PartitionEvaluator> initPartitionPlans(
      TableFileScanHelper tableFileScanHelper, OptimizingConfig config) {
    Map<String, PartitionEvaluator> partitionPlanMap = Maps.newHashMap();

    long count = 0;
    try (CloseableIterable<TableFileScanHelper.FileScanResult> results =
        tableFileScanHelper.scan()) {
      for (TableFileScanHelper.FileScanResult fileScanResult : results) {
        PartitionSpec partitionSpec = tableFileScanHelper.getSpec(fileScanResult.file().specId());
        StructLike partition = fileScanResult.file().partition();
        String partitionPath = partitionSpec.partitionToPath(partition);
        PartitionEvaluator evaluator =
            partitionPlanMap.computeIfAbsent(
                partitionPath,
                ignore -> buildEvaluator(Pair.of(partitionSpec.specId(), partition), config));
        evaluator.addFile(fileScanResult.file(), fileScanResult.deleteFiles());
        count++;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return partitionPlanMap;
  }

  private PartitionEvaluator buildEvaluator(
      Pair<Integer, StructLike> partition, OptimizingConfig config) {
    return new CommonPartitionEvaluator(
        ServerTableIdentifier.of(TableTestHelper.TEST_TABLE_ID, TableFormat.ICEBERG),
        config,
        partition,
        System.currentTimeMillis(),
        0L,
        0L,
        0L);
  }

  @Test
  public void test_setFileSizeMSETolerance() {
    OptimizingConfig config = new OptimizingConfig();
    Assert.assertEquals(0, config.getEvaluationMseTolerance());

    config.setEvaluationMseTolerance(1000);
    Assert.assertEquals(1000, config.getEvaluationMseTolerance());

    config.setEvaluationMseTolerance(140000000);
    Assert.assertEquals(140000000, config.getEvaluationMseTolerance());
  }

  @Test
  public void testBasicStatsAccept() {
    IcebergTableStatsProvider.BasicFileStats stats = new IcebergTableStatsProvider.BasicFileStats();
    // Initial state should be zeros
    Assert.assertEquals(0, stats.deleteFileCnt);
    Assert.assertEquals(0, stats.dataFileCnt);
    Assert.assertEquals(0, stats.totalFileSize);
    // Create first summary map
    Map<String, String> summary1 = new HashMap<>();
    summary1.put("total-delete-files", "5");
    summary1.put("total-data-files", "10");
    summary1.put("total-files-size", "1024");
    stats.accept(summary1);
    Assert.assertEquals(5, stats.deleteFileCnt);
    Assert.assertEquals(10, stats.dataFileCnt);
    Assert.assertEquals(1024, stats.totalFileSize);
    // Create second summary map to test accumulation
    Map<String, String> summary2 = new HashMap<>();
    summary2.put("total-delete-files", "3");
    summary2.put("total-data-files", "7");
    summary2.put("total-files-size", "2048");
    stats.accept(summary2);
    // Values should be accumulated
    Assert.assertEquals(8, stats.deleteFileCnt); // 5 + 3
    Assert.assertEquals(17, stats.dataFileCnt); // 10 + 7
    Assert.assertEquals(3072, stats.totalFileSize); // 1024 + 2048
  }

  @Test
  public void testBasicTableStatsAcceptWithMissingProperties() {
    IcebergTableStatsProvider.BasicFileStats stats = new IcebergTableStatsProvider.BasicFileStats();
    // Summary map with missing properties should use default values
    Map<String, String> summary = new HashMap<>();
    // Only provide one property, others should default to 0
    summary.put("total-data-files", "15");
    stats.accept(summary);
    Assert.assertEquals(0, stats.deleteFileCnt); // default value
    Assert.assertEquals(15, stats.dataFileCnt);
    Assert.assertEquals(0, stats.totalFileSize); // default value
  }

  @Test
  public void testBasicTableStatsAcceptWithInvalidValues() {
    IcebergTableStatsProvider.BasicFileStats stats = new IcebergTableStatsProvider.BasicFileStats();
    // Summary map with invalid numeric values should use default values
    Map<String, String> summary = new HashMap<>();
    summary.put("total-delete-files", "invalid");
    summary.put("total-data-files", "20");
    summary.put("total-files-size", "not-a-number");

    // Invalid values should throw NumberFormatException
    Assert.assertThrows(NumberFormatException.class, () -> stats.accept(summary));
  }

  OptimizingConfig getDefaultOptimizingConfig() {
    return new OptimizingConfig()
        .setEnabled(TableProperties.ENABLE_SELF_OPTIMIZING_DEFAULT)
        .setAllowPartialCommit(TableProperties.SELF_OPTIMIZING_ALLOW_PARTIAL_COMMIT_DEFAULT)
        .setMaxExecuteRetryCount(TableProperties.SELF_OPTIMIZING_EXECUTE_RETRY_NUMBER_DEFAULT)
        .setOptimizerGroup(TableProperties.SELF_OPTIMIZING_GROUP_DEFAULT)
        .setFragmentRatio(TableProperties.SELF_OPTIMIZING_FRAGMENT_RATIO_DEFAULT)
        .setMinTargetSizeRatio(TableProperties.SELF_OPTIMIZING_MIN_TARGET_SIZE_RATIO_DEFAULT)
        .setMaxFileCount(TableProperties.SELF_OPTIMIZING_MAX_FILE_CNT_DEFAULT)
        .setOpenFileCost(TableProperties.SPLIT_OPEN_FILE_COST_DEFAULT)
        .setTargetSize(TableProperties.SELF_OPTIMIZING_TARGET_SIZE_DEFAULT)
        .setMaxTaskSize(TableProperties.SELF_OPTIMIZING_MAX_TASK_SIZE_DEFAULT)
        .setTargetQuota(TableProperties.SELF_OPTIMIZING_QUOTA_DEFAULT)
        .setMinorLeastFileCount(TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_FILE_CNT_DEFAULT)
        .setMajorDuplicateRatio(
            TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_DUPLICATE_RATIO_DEFAULT)
        .setFullRewriteAllFiles(TableProperties.SELF_OPTIMIZING_FULL_REWRITE_ALL_FILES_DEFAULT)
        .setFilter(TableProperties.SELF_OPTIMIZING_FILTER_DEFAULT)
        .setBaseHashBucket(TableProperties.BASE_FILE_INDEX_HASH_BUCKET_DEFAULT)
        .setBaseRefreshInterval(TableProperties.BASE_REFRESH_INTERVAL_DEFAULT)
        .setMinPlanInterval(TableProperties.SELF_OPTIMIZING_MIN_PLAN_INTERVAL_DEFAULT)
        .setEvaluationFallbackInterval(
            TableProperties.SELF_OPTIMIZING_EVALUATION_FALLBACK_INTERVAL_DEFAULT)
        .setEvaluationMseTolerance(
            TableProperties.SELF_OPTIMIZING_EVALUATION_FILE_SIZE_MSE_TOLERANCE_DEFAULT);
  }
}
