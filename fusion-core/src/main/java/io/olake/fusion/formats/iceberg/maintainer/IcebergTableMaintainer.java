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

import static org.apache.amoro.shade.guava32.com.google.common.primitives.Longs.min;

import io.olake.fusion.FusionTable;
import io.olake.fusion.api.CommitMetaProducer;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.formats.iceberg.utils.IcebergTableUtil;
import io.olake.fusion.formats.iceberg.utils.RollingFileCleaner;
import io.olake.fusion.io.AuthenticatedFileIO;
import io.olake.fusion.io.PathInfo;
import io.olake.fusion.io.SupportsFileSystemOperations;
import io.olake.fusion.maintainer.MaintainerMetrics;
import io.olake.fusion.maintainer.OptimizingInfo;
import io.olake.fusion.maintainer.TableMaintainer;
import io.olake.fusion.maintainer.TableMaintainerContext;
import io.olake.fusion.table.TableIdentifier;
import io.olake.fusion.utils.TableFileUtil;
import org.apache.amoro.shade.guava32.com.google.common.annotations.VisibleForTesting;
import org.apache.amoro.shade.guava32.com.google.common.base.Strings;
import org.apache.amoro.shade.guava32.com.google.common.collect.Iterables;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ReachableFileUtil;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.SupportsPrefixOperations;
import org.apache.iceberg.util.DateTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Table maintainer for Iceberg tables. */
public class IcebergTableMaintainer implements TableMaintainer {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergTableMaintainer.class);

  public static final String METADATA_FOLDER_NAME = "metadata";
  public static final String DATA_FOLDER_NAME = "data";
  // same as org.apache.iceberg.flink.sink.IcebergFilesCommitter#FLINK_JOB_ID
  public static final String FLINK_JOB_ID = "flink.job-id";
  // same as org.apache.iceberg.flink.sink.IcebergFilesCommitter#MAX_COMMITTED_CHECKPOINT_ID
  public static final String FLINK_MAX_COMMITTED_CHECKPOINT_ID =
      "flink.max-committed-checkpoint-id";

  public static final Set<String> AMORO_MAINTAIN_COMMITS =
      Sets.newHashSet(
          CommitMetaProducer.OPTIMIZE.name(),
          CommitMetaProducer.DATA_EXPIRATION.name(),
          CommitMetaProducer.CLEAN_DANGLING_DELETE.name());

  public Table table;
  private final AuthenticatedFileIO fileIO;
  private final TableIdentifier tableIdentifier;
  private final TableMaintainerContext context;

  public IcebergTableMaintainer(FusionTable table, TableMaintainerContext context) {
    this(table.table(), table.io(), table.id(), context);
  }

  /**
   * @param table the Iceberg table
   * @param fileIO the table's file IO, authenticated with the catalog's credentials; it lists and
   *     deletes files
   * @param tableIdentifier the Fusion identifier of the table
   * @param context maintenance configuration and metrics
   */
  public IcebergTableMaintainer(
      Table table,
      AuthenticatedFileIO fileIO,
      TableIdentifier tableIdentifier,
      TableMaintainerContext context) {
    this.table = table;
    this.fileIO = fileIO;
    this.tableIdentifier = tableIdentifier;
    this.context = context;
  }

  @Override
  public void cleanOrphanFiles() {
    TableConfiguration tableConfiguration = context.getTableConfiguration();
    MaintainerMetrics metrics = context.getMetrics();

    if (!tableConfiguration.isCleanOrphanEnabled()) {
      return;
    }

    long keepTime = tableConfiguration.getOrphanExistingMinutes() * 60 * 1000;

    cleanContentFiles(System.currentTimeMillis() - keepTime, metrics);

    // refresh
    table.refresh();

    // clear metadata files
    cleanMetadata(System.currentTimeMillis() - keepTime, metrics);
  }

  @Override
  public void cleanDanglingDeleteFiles() {
    TableConfiguration tableConfiguration = context.getTableConfiguration();
    if (!tableConfiguration.isDeleteDanglingDeleteFilesEnabled()) {
      return;
    }

    Snapshot currentSnapshot = table.currentSnapshot();
    if (currentSnapshot == null) {
      return;
    }
    Optional<String> totalDeleteFiles =
        Optional.ofNullable(currentSnapshot.summary().get(SnapshotSummary.TOTAL_DELETE_FILES_PROP));
    if (totalDeleteFiles.isPresent() && Long.parseLong(totalDeleteFiles.get()) > 0) {
      // clear dangling delete files
      doCleanDanglingDeleteFiles();
    } else {
      LOG.debug(
          "There are no delete files here, so there is no need to clean dangling delete file for table {}",
          table.name());
    }
  }

  @Override
  public void expireSnapshots() {
    if (!expireSnapshotEnabled()) {
      return;
    }
    expireSnapshots(
        mustOlderThan(System.currentTimeMillis()),
        context.getTableConfiguration().getSnapshotMinCount());
  }

  public boolean expireSnapshotEnabled() {
    TableConfiguration tableConfiguration = context.getTableConfiguration();
    return tableConfiguration.isExpireSnapshotEnabled();
  }

  @VisibleForTesting
  public void expireSnapshots(long mustOlderThan, int minCount) {
    expireSnapshots(mustOlderThan, minCount, expireSnapshotNeedToExcludeFiles());
  }

  private void expireSnapshots(long olderThan, int minCount, Set<String> exclude) {
    LOG.debug(
        "Starting snapshots expiration for table {}, expiring snapshots older than {} and retain last {} snapshots, excluding {}",
        table.name(),
        olderThan,
        minCount,
        exclude);
    RollingFileCleaner expiredFileCleaner = new RollingFileCleaner(fileIO(), exclude);
    table
        .expireSnapshots()
        .retainLast(Math.max(minCount, 1))
        .expireOlderThan(olderThan)
        .deleteWith(expiredFileCleaner::addFile)
        .cleanExpiredFiles(
            true) /* enable clean only for collecting the expired files, will delete them later */
        .commit();

    int collectedFiles = expiredFileCleaner.fileCount();
    expiredFileCleaner.clear();
    if (collectedFiles > 0) {
      LOG.info(
          "Expired {}/{} files for table {} order than {}",
          collectedFiles,
          expiredFileCleaner.cleanedFileCount(),
          table.name(),
          DateTimeUtil.formatTimestampMillis(olderThan));
    } else {
      LOG.debug(
          "No expired files found for table {} order than {}",
          table.name(),
          DateTimeUtil.formatTimestampMillis(olderThan));
    }
  }

  public void cleanContentFiles(long lastTime, MaintainerMetrics metrics) {
    // For clean data files, should getRuntime valid files in the base store and the change store,
    // so acquire in advance
    // to prevent repeated acquisition
    Set<String> validFiles = orphanFileCleanNeedToExcludeFiles();
    LOG.info(
        "Starting cleaning orphan content files for table {} before {}",
        table.name(),
        Instant.ofEpochMilli(lastTime));
    clearInternalTableContentsFiles(lastTime, validFiles, metrics);
  }

  public void cleanMetadata(long lastTime, MaintainerMetrics metrics) {
    LOG.info(
        "Starting cleaning metadata files for table {} before {}",
        table.name(),
        Instant.ofEpochMilli(lastTime));
    clearInternalTableMetadata(lastTime, metrics);
  }

  public void doCleanDanglingDeleteFiles() {
    LOG.info("Starting cleaning dangling delete files for table {}", table.name());
    int danglingDeleteFilesCnt = clearInternalTableDanglingDeleteFiles();
    runWithCondition(
        danglingDeleteFilesCnt > 0,
        () ->
            LOG.info(
                "Deleted {} dangling delete files for table {}",
                danglingDeleteFilesCnt,
                table.name()));
  }

  public long mustOlderThan(long now) {
    long mustOlderThan =
        min(
            // The snapshots keep time
            now - snapshotsKeepTime(),
            // The snapshot optimizing plan based should not be expired for committing
            fetchOptimizingPlanSnapshotTime(table),
            // The latest non-optimized snapshot should not be expired for data expiring
            fetchLatestNonOptimizedSnapshotTime(table));

    long latestFlinkCommitTime = fetchLatestFlinkCommittedSnapshotTime(table);
    long flinkCkRetainMillis = context.getTableConfiguration().getFlinkCheckpointRetention();
    if ((now - latestFlinkCommitTime) > flinkCkRetainMillis) {
      // exceed configured flink checkpoint retain time, no need to consider flink committed
      // snapshot
      return mustOlderThan;
    } else {
      // keep at least flink committed snapshot for flink job recovering
      return min(mustOlderThan, latestFlinkCommitTime);
    }
  }

  public long snapshotsKeepTime() {
    return context.getTableConfiguration().getSnapshotTTLMinutes() * 60 * 1000;
  }

  protected Set<String> expireSnapshotNeedToExcludeFiles() {
    return Collections.emptySet();
  }

  public Set<String> orphanFileCleanNeedToExcludeFiles() {
    return Sets.union(
        IcebergTableUtil.getAllContentFilePath(table),
        IcebergTableUtil.getAllStatisticsFilePath(table));
  }

  protected AuthenticatedFileIO fileIO() {
    return fileIO;
  }

  private void clearInternalTableContentsFiles(
      long lastTime, Set<String> exclude, MaintainerMetrics metrics) {
    String dataLocation = table.location() + File.separator + DATA_FOLDER_NAME;
    int expected = 0, deleted = 0;

    AuthenticatedFileIO io = fileIO();
    // listPrefix will not return the directory and the orphan file clean should clean the empty
    // dir.
    if (io.supportFileSystemOperations()) {
      SupportsFileSystemOperations fio = io.asFileSystemIO();
      Set<PathInfo> directories = new HashSet<>();
      Set<String> filesToDelete =
          deleteInvalidFilesInFs(fio, dataLocation, lastTime, exclude, directories);
      expected = filesToDelete.size();
      deleted = TableFileUtil.deleteFiles(io, filesToDelete);
      /* delete empty directories */
      deleteEmptyDirectories(fio, directories, lastTime, exclude);
    } else if (io.supportPrefixOperations()) {
      SupportsPrefixOperations pio = io.asPrefixFileIO();
      Set<String> filesToDelete = deleteInvalidFilesByPrefix(pio, dataLocation, lastTime, exclude);
      expected = filesToDelete.size();
      deleted = TableFileUtil.deleteFiles(io, filesToDelete);
    } else {
      LOG.warn(
          String.format(
              "Table %s doesn't support a fileIo with listDirectory or listPrefix, so skip clear files.",
              table.name()));
    }

    final int finalExpected = expected;
    final int finalDeleted = deleted;
    runWithCondition(
        expected > 0,
        () -> {
          LOG.info(
              "Deleted {}/{} orphan content files for table {}",
              finalDeleted,
              finalExpected,
              table.name());
          metrics.recordOrphanDataFilesCleaned(finalExpected, finalDeleted);
        });
  }

  private void clearInternalTableMetadata(long lastTime, MaintainerMetrics metrics) {
    Set<String> validFiles = getValidMetadataFiles(table);
    LOG.info("Found {} valid metadata files for table {}", validFiles.size(), table.name());
    Pattern excludeFileNameRegex = getExcludeFileNameRegex(table);
    LOG.info(
        "Exclude metadata files with name pattern {} for table {}",
        excludeFileNameRegex,
        table.name());
    String metadataLocation = table.location() + File.separator + METADATA_FOLDER_NAME;
    LOG.info("start orphan files clean in {}", metadataLocation);

    AuthenticatedFileIO io = fileIO();
    if (io.supportPrefixOperations()) {
      SupportsPrefixOperations pio = io.asPrefixFileIO();
      Set<String> filesToDelete =
          deleteInvalidMetadataFile(
              pio, metadataLocation, lastTime, validFiles, excludeFileNameRegex);
      int deleted = TableFileUtil.deleteFiles(io, filesToDelete);

      runWithCondition(
          !filesToDelete.isEmpty(),
          () -> {
            LOG.info(
                "Deleted {}/{} metadata files for table {}",
                deleted,
                filesToDelete.size(),
                table.name());
            metrics.recordOrphanMetadataFilesCleaned(filesToDelete.size(), deleted);
          });
    } else {
      LOG.warn(
          String.format(
              "Table %s doesn't support a fileIo with listDirectory or listPrefix, so skip clear files.",
              table.name()));
    }
  }

  private int clearInternalTableDanglingDeleteFiles() {
    Set<DeleteFile> danglingDeleteFiles = IcebergTableUtil.getDanglingDeleteFiles(table);
    if (danglingDeleteFiles.isEmpty()) {
      return 0;
    }
    RewriteFiles rewriteFiles = table.newRewrite();
    rewriteFiles.rewriteFiles(
        Collections.emptySet(),
        danglingDeleteFiles,
        Collections.emptySet(),
        Collections.emptySet());
    try {
      rewriteFiles.set(
          io.olake.fusion.op.SnapshotSummary.SNAPSHOT_PRODUCER,
          CommitMetaProducer.CLEAN_DANGLING_DELETE.name());
      rewriteFiles.commit();
    } catch (ValidationException e) {
      LOG.warn(
          "Failed to commit dangling delete file for table {}, but ignore it", table.name(), e);
      return 0;
    }
    return danglingDeleteFiles.size();
  }

  /**
   * When committing a snapshot, Flink will write a checkpoint id into the snapshot summary, which
   * will be used when Flink job recovers from the checkpoint.
   *
   * @param table table
   * @return commit time of snapshot with the latest flink checkpointId in summary, return
   *     Long.MAX_VALUE if not exist
   */
  public static long fetchLatestFlinkCommittedSnapshotTime(Table table) {
    Snapshot snapshot = findLatestSnapshotContainsKey(table, FLINK_MAX_COMMITTED_CHECKPOINT_ID);
    return snapshot == null ? Long.MAX_VALUE : snapshot.timestampMillis();
  }

  /**
   * When the current optimizing process not committed, get the time of snapshot for optimizing
   * process planned based. This snapshot will be used when optimizing process committing.
   *
   * @param table table
   * @return time of snapshot for optimizing process planned based, return Long.MAX_VALUE if no
   *     optimizing process exists
   */
  public long fetchOptimizingPlanSnapshotTime(Table table) {
    OptimizingInfo optimizingInfo = context.getOptimizingInfo();
    if (optimizingInfo.isProcessing()) {
      long fromSnapshotId = optimizingInfo.getTargetSnapshotId();

      for (Snapshot snapshot : table.snapshots()) {
        if (snapshot.snapshotId() == fromSnapshotId) {
          return snapshot.timestampMillis();
        }
      }
    }
    return Long.MAX_VALUE;
  }

  public static Snapshot findLatestSnapshotContainsKey(Table table, String summaryKey) {
    Snapshot latestSnapshot = null;
    for (Snapshot snapshot : table.snapshots()) {
      if (snapshot.summary().containsKey(summaryKey)) {
        latestSnapshot = snapshot;
      }
    }
    return latestSnapshot;
  }

  /**
   * When expiring historic data and `data-expire.base-on-rule` is `LAST_COMMIT_TIME`, the latest
   * snapshot should not be produced by Amoro optimizing.
   *
   * @param table iceberg table
   * @return the latest non-optimized snapshot timestamp
   */
  public static long fetchLatestNonOptimizedSnapshotTime(Table table) {
    Optional<Snapshot> snapshot =
        IcebergTableUtil.findFirstMatchSnapshot(
            table, s -> s.summary().values().stream().noneMatch(AMORO_MAINTAIN_COMMITS::contains));
    return snapshot.map(Snapshot::timestampMillis).orElse(Long.MAX_VALUE);
  }

  private Set<String> deleteInvalidFilesInFs(
      SupportsFileSystemOperations fio,
      String location,
      long lastTime,
      Set<String> excludes,
      Set<PathInfo> directories) {
    if (!fio.exists(location)) {
      return Collections.emptySet();
    }

    Set<String> filesToDelete = new HashSet<>();
    for (PathInfo p : fio.listDirectory(location)) {
      String uriPath = TableFileUtil.getUriPath(p.location());
      if (p.isDirectory()) {
        directories.add(p);
        filesToDelete.addAll(
            deleteInvalidFilesInFs(fio, p.location(), lastTime, excludes, directories));
      } else {
        String parentLocation = TableFileUtil.getParent(p.location());
        String parentUriPath = TableFileUtil.getUriPath(parentLocation);
        if (!excludes.contains(uriPath)
            && !excludes.contains(parentUriPath)
            && p.createdAtMillis() < lastTime) {
          filesToDelete.add(p.location());
        }
      }
    }
    return filesToDelete;
  }

  private void deleteEmptyDirectories(
      SupportsFileSystemOperations fio, Set<PathInfo> paths, long lastTime, Set<String> excludes) {
    paths.forEach(
        p -> {
          if (fio.exists(p.location())
              && !p.location().endsWith(METADATA_FOLDER_NAME)
              && !p.location().endsWith(DATA_FOLDER_NAME)
              && p.createdAtMillis() < lastTime
              && fio.isEmptyDirectory(p.location())) {
            TableFileUtil.deleteEmptyDirectory(fio, p.location(), excludes);
          }
        });
  }

  private Set<String> deleteInvalidFilesByPrefix(
      SupportsPrefixOperations pio, String prefix, long lastTime, Set<String> excludes) {
    Set<String> filesToDelete = new HashSet<>();
    for (FileInfo fileInfo : pio.listPrefix(prefix)) {
      String uriPath = TableFileUtil.getUriPath(fileInfo.location());
      if (!excludes.contains(uriPath) && fileInfo.createdAtMillis() < lastTime) {
        filesToDelete.add(fileInfo.location());
      }
    }

    return filesToDelete;
  }

  private static Set<String> getValidMetadataFiles(Table internalTable) {
    String tableName = internalTable.name();
    Set<String> validFiles = new HashSet<>();
    Iterable<Snapshot> snapshots = internalTable.snapshots();
    int size = Iterables.size(snapshots);
    LOG.info("Found {} snapshots to scan for table {}", size, tableName);
    for (Snapshot snapshot : snapshots) {
      String manifestListLocation = snapshot.manifestListLocation();
      validFiles.add(TableFileUtil.getUriPath(manifestListLocation));
    }
    // valid data files
    Set<String> allManifestFiles = IcebergTableUtil.getAllManifestFiles(internalTable);
    allManifestFiles.forEach(f -> validFiles.add(TableFileUtil.getUriPath(f)));

    Stream.of(
            ReachableFileUtil.metadataFileLocations(internalTable, false).stream(),
            ReachableFileUtil.statisticsFilesLocations(internalTable).stream(),
            Stream.of(ReachableFileUtil.versionHintLocation(internalTable)))
        .reduce(Stream::concat)
        .orElse(Stream.empty())
        .map(TableFileUtil::getUriPath)
        .forEach(validFiles::add);

    return validFiles;
  }

  private static Pattern getExcludeFileNameRegex(Table table) {
    String latestFlinkJobId = null;
    for (Snapshot snapshot : table.snapshots()) {
      String flinkJobId = snapshot.summary().get(FLINK_JOB_ID);
      if (!Strings.isNullOrEmpty(flinkJobId)) {
        latestFlinkJobId = flinkJobId;
      }
    }
    if (latestFlinkJobId != null) {
      // file name starting with flink.job-id should not be deleted
      return Pattern.compile(latestFlinkJobId + ".*");
    }
    return null;
  }

  private Set<String> deleteInvalidMetadataFile(
      SupportsPrefixOperations pio,
      String location,
      long lastTime,
      Set<String> exclude,
      Pattern excludeRegex) {
    Set<String> filesToDelete = new HashSet<>();
    for (FileInfo fileInfo : pio.listPrefix(location)) {
      String uriPath = TableFileUtil.getUriPath(fileInfo.location());
      if (!exclude.contains(uriPath)
          && fileInfo.createdAtMillis() < lastTime
          && (excludeRegex == null
              || !excludeRegex.matcher(TableFileUtil.getFileName(fileInfo.location())).matches())) {
        filesToDelete.add(fileInfo.location());
      }
    }

    return filesToDelete;
  }

  public Table getTable() {
    return table;
  }

  private void runWithCondition(boolean condition, Runnable fun) {
    if (condition) {
      fun.run();
    }
  }
}
