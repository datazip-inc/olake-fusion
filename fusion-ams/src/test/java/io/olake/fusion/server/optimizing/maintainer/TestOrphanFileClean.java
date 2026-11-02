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

import static io.olake.fusion.formats.iceberg.maintainer.IcebergTableMaintainer.DATA_FOLDER_NAME;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.formats.iceberg.maintainer.IcebergTableMaintainer;
import io.olake.fusion.server.scheduler.inline.ExecutorTestBase;
import io.olake.fusion.server.table.TableOrphanFilesCleaningMetrics;
import io.olake.fusion.table.TableIdentifier;
import io.olake.fusion.table.TableProperties;
import org.apache.iceberg.GenericBlobMetadata;
import org.apache.iceberg.GenericStatisticsFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.puffin.BlobMetadata;
import org.apache.iceberg.puffin.Puffin;
import org.apache.iceberg.puffin.PuffinWriter;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class TestOrphanFileClean extends ExecutorTestBase {

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)},
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, false)}
    };
  }

  public TestOrphanFileClean(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  @Test
  public void orphanDataFileClean() throws IOException {
    writeAndCommitBaseStore(getTable());

    Table baseTable = getTable();
    String baseOrphanFileDir =
        baseTable.location() + File.separator + DATA_FOLDER_NAME + File.separator + "testLocation";
    String baseOrphanFilePath = baseOrphanFileDir + File.separator + "orphan.parquet";
    OutputFile baseOrphanDataFile = getFusionTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFileDir));
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFilePath));

    TableIdentifier tableIdentifier = getFusionTable().id();
    TableOrphanFilesCleaningMetrics orphanFilesCleaningMetrics =
        new TableOrphanFilesCleaningMetrics(
            ServerTableIdentifier.of(
                tableIdentifier.getCatalog(),
                tableIdentifier.getDatabase(),
                tableIdentifier.getTableName(),
                getTestFormat()));
    IcebergTableMaintainer maintainer =
        new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()));
    maintainer.cleanContentFiles(
        System.currentTimeMillis()
            - TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME_DEFAULT * 60 * 1000,
        orphanFilesCleaningMetrics);
    maintainer.cleanMetadata(
        System.currentTimeMillis()
            - TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME_DEFAULT * 60 * 1000,
        orphanFilesCleaningMetrics);

    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFileDir));
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFilePath));

    maintainer.cleanContentFiles(System.currentTimeMillis(), orphanFilesCleaningMetrics);
    maintainer.cleanMetadata(System.currentTimeMillis(), orphanFilesCleaningMetrics);

    // Orphan cleanup deletes files only; an emptied directory stays (S3 has no directories).
    Assert.assertFalse(getFusionTable().io().exists(baseOrphanFilePath));
    baseTable
        .newScan()
        .planFiles()
        .forEach(
            task -> Assert.assertTrue(getFusionTable().io().exists(task.file().path().toString())));
  }

  @Test
  public void orphanMetadataFileClean() throws IOException {
    writeAndCommitBaseStore(getTable());

    Table baseTable = getTable();
    String baseOrphanFilePath =
        baseTable.location() + File.separator + "metadata" + File.separator + "orphan.avro";

    OutputFile baseOrphanDataFile = getFusionTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFilePath));

    IcebergTableMaintainer maintainer =
        new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()));
    TableIdentifier tableIdentifier = getFusionTable().id();
    TableOrphanFilesCleaningMetrics orphanFilesCleaningMetrics =
        new TableOrphanFilesCleaningMetrics(
            ServerTableIdentifier.of(
                tableIdentifier.getCatalog(),
                tableIdentifier.getDatabase(),
                tableIdentifier.getTableName(),
                getTestFormat()));
    maintainer.cleanMetadata(System.currentTimeMillis(), orphanFilesCleaningMetrics);

    Assert.assertFalse(getFusionTable().io().exists(baseOrphanFilePath));
    ExecutorTestBase.assertMetadataExists(getTable());
  }

  @Test
  public void notDeleteFlinkTemporaryFile() throws IOException {
    writeAndCommitBaseStore(getTable());
    String flinkJobId = "flinkJobTest";

    Table baseTable = getTable();
    String baseOrphanFilePath =
        baseTable.location()
            + File.separator
            + "metadata"
            + File.separator
            + flinkJobId
            + "orphan.avro";

    OutputFile baseOrphanDataFile = getFusionTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFilePath));

    IcebergTableMaintainer tableMaintainer =
        new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()));
    TableIdentifier tableIdentifier = getFusionTable().id();
    TableOrphanFilesCleaningMetrics orphanFilesCleaningMetrics =
        new TableOrphanFilesCleaningMetrics(
            ServerTableIdentifier.of(
                tableIdentifier.getCatalog(),
                tableIdentifier.getDatabase(),
                tableIdentifier.getTableName(),
                getTestFormat()));

    tableMaintainer.cleanMetadata(System.currentTimeMillis(), orphanFilesCleaningMetrics);
    Assert.assertFalse(getFusionTable().io().exists(baseOrphanFilePath));

    ExecutorTestBase.assertMetadataExists(getTable());
  }

  @Test
  public void notDeleteStatisticsFile() {
    Table unkeyedTable;
    unkeyedTable = getTable();
    StatisticsFile file1 =
        commitStatisticsFile(unkeyedTable, unkeyedTable.location() + "/metadata/test1.puffin");
    StatisticsFile file2 =
        commitStatisticsFile(unkeyedTable, unkeyedTable.location() + "/data/test2.puffin");
    StatisticsFile file3 =
        commitStatisticsFile(unkeyedTable, unkeyedTable.location() + "/data/puffin/test3.puffin");

    TableIdentifier tableIdentifier = getFusionTable().id();
    TableOrphanFilesCleaningMetrics orphanFilesCleaningMetrics =
        new TableOrphanFilesCleaningMetrics(
            ServerTableIdentifier.of(
                tableIdentifier.getCatalog(),
                tableIdentifier.getDatabase(),
                tableIdentifier.getTableName(),
                getTestFormat()));

    Assert.assertTrue(unkeyedTable.io().newInputFile(String.valueOf(file1.path())).exists());
    Assert.assertTrue(unkeyedTable.io().newInputFile(String.valueOf(file2.path())).exists());
    Assert.assertTrue(unkeyedTable.io().newInputFile(String.valueOf(file3.path())).exists());
    new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()))
        .cleanContentFiles(System.currentTimeMillis() + 1, orphanFilesCleaningMetrics);
    new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()))
        .cleanMetadata(System.currentTimeMillis() + 1, orphanFilesCleaningMetrics);
    Assert.assertTrue(unkeyedTable.io().newInputFile(String.valueOf(file1.path())).exists());
    Assert.assertTrue(unkeyedTable.io().newInputFile(String.valueOf(file2.path())).exists());
    Assert.assertTrue(unkeyedTable.io().newInputFile(String.valueOf(file3.path())).exists());
  }

  @Test
  public void testGcDisabled() throws IOException {
    writeAndCommitBaseStore(getTable());

    Table baseTable = getTable();
    baseTable
        .updateProperties()
        .set(TableProperties.ENABLE_ORPHAN_CLEAN, "true")
        .set(TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME, "0")
        .set("gc.enabled", "false")
        .commit();

    String baseOrphanFileDir =
        baseTable.location() + File.separator + DATA_FOLDER_NAME + File.separator + "testLocation";
    String baseOrphanFilePath = baseOrphanFileDir + File.separator + "orphan.parquet";
    OutputFile baseOrphanDataFile = getFusionTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFileDir));
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFilePath));

    IcebergTableMaintainer maintainer =
        new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()));
    maintainer.cleanOrphanFiles();

    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFileDir));
    Assert.assertTrue(getFusionTable().io().exists(baseOrphanFilePath));

    baseTable.updateProperties().set("gc.enabled", "true").commit();
    maintainer =
        new IcebergTableMaintainer(
            getTable(),
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(getTable()));
    maintainer.cleanOrphanFiles();

    // Orphan cleanup deletes files only; an emptied directory stays (S3 has no directories).
    Assert.assertFalse(getFusionTable().io().exists(baseOrphanFilePath));
  }

  private StatisticsFile commitStatisticsFile(Table table, String fileLocation) {
    table.newAppend().commit();
    Snapshot snapshot = table.currentSnapshot();
    OutputFile outputFile = table.io().newOutputFile(fileLocation);
    List<BlobMetadata> blobMetadata;
    long fileSize;
    long footerSize;
    try (PuffinWriter puffinWriter = Puffin.write(outputFile).build()) {
      puffinWriter.finish();
      blobMetadata = puffinWriter.writtenBlobsMetadata();
      fileSize = puffinWriter.fileSize();
      footerSize = puffinWriter.footerSize();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    List<org.apache.iceberg.BlobMetadata> collect =
        blobMetadata.stream().map(GenericBlobMetadata::from).collect(Collectors.toList());
    GenericStatisticsFile statisticsFile =
        new GenericStatisticsFile(
            snapshot.snapshotId(), outputFile.location(), fileSize, footerSize, collect);
    table.updateStatistics().setStatistics(snapshot.snapshotId(), statisticsFile).commit();

    return statisticsFile;
  }
}
