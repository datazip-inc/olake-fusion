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

package io.olake.fusion.server.optimizing;

import io.olake.fusion.api.CommitMetaProducer;
import io.olake.fusion.exception.OptimizingCommitException;
import io.olake.fusion.iceberg.Constants;
import io.olake.fusion.op.SnapshotSummary;
import io.olake.fusion.optimizing.RewriteStageTask;
import io.olake.fusion.server.optimizing.TaskRuntime.Status;
import io.olake.fusion.table.MixedTable;
import io.olake.fusion.utils.ContentFiles;
import io.olake.fusion.utils.IcebergThreadPools;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UnKeyedTableCommit {
  private static final Logger LOG = LoggerFactory.getLogger(UnKeyedTableCommit.class);

  private final Long targetSnapshotId;
  private final MixedTable table;
  private final Collection<TaskRuntime<RewriteStageTask>> tasks;

  public UnKeyedTableCommit(
      Long targetSnapshotId, MixedTable table, Collection<TaskRuntime<RewriteStageTask>> tasks) {
    this.targetSnapshotId = targetSnapshotId;
    this.table = table;
    this.tasks = tasks;
  }

  private Set<ContentFile<?>> getExcludedDeleteFiles(
      List<TaskRuntime<RewriteStageTask>> successTasks) {
    Set<ContentFile<?>> excludedDeleteFiles = new HashSet<>();
    tasks.stream()
        .filter(task -> !successTasks.contains(task))
        .map(TaskRuntime::getTaskDescriptor)
        .filter(task -> task.getInput().rewrittenDeleteFiles() != null)
        .forEach(
            task ->
                excludedDeleteFiles.addAll(
                    Arrays.stream(task.getInput().rewrittenDeleteFiles())
                        .collect(Collectors.toSet())));
    return excludedDeleteFiles;
  }

  private boolean needRemove(Set<ContentFile<?>> excludedDeleteFiles, ContentFile<?> deleteFile) {
    return !excludedDeleteFiles.contains(deleteFile);
  }

  public void commit() throws OptimizingCommitException {
    List<TaskRuntime<RewriteStageTask>> successTasks =
        tasks.stream()
            .filter(task -> task.getStatus() == Status.SUCCESS)
            .collect(Collectors.toList());
    if (successTasks.isEmpty()) {
      LOG.info("No tasks to commit for table {}", table.id());
      return;
    }

    long startTime = System.currentTimeMillis();
    LOG.info("Starting to commit table {} with {} tasks.", table.id(), tasks.size());

    Set<ContentFile<?>> excludedDeleteFiles = getExcludedDeleteFiles(successTasks);
    // collect files
    Set<DataFile> addedDataFiles = Sets.newHashSet();
    Set<DataFile> removedDataFiles = Sets.newHashSet();
    Set<DeleteFile> addedDeleteFiles = Sets.newHashSet();
    Set<DeleteFile> removedDeleteFiles = Sets.newHashSet();
    successTasks.stream()
        .map(TaskRuntime::getTaskDescriptor)
        .forEach(
            task -> {
              if (task.getOutput().getDataFiles() != null) {
                addedDataFiles.addAll(Arrays.asList(task.getOutput().getDataFiles()));
              }
              if (task.getOutput().getDeleteFiles() != null) {
                addedDeleteFiles.addAll(Arrays.asList(task.getOutput().getDeleteFiles()));
              }
              if (task.getInput().rewrittenDataFiles() != null) {
                removedDataFiles.addAll(Arrays.asList(task.getInput().rewrittenDataFiles()));
              }
              if (task.getInput().rewrittenDeleteFiles() != null) {
                removedDeleteFiles.addAll(
                    Arrays.stream(task.getInput().rewrittenDeleteFiles())
                        .filter(deleteFile -> needRemove(excludedDeleteFiles, deleteFile))
                        .map(ContentFiles::asDeleteFile)
                        .collect(Collectors.toSet()));
              }
            });
    try {
      Transaction transaction = table.asUnkeyedTable().newTransaction();
      if (removedDeleteFiles.isEmpty() && !addedDeleteFiles.isEmpty()) {
        /* In order to avoid the validation in
        {@link org.apache.iceberg.BaseRewriteFiles#validateReplacedAndAddedFiles} which will throw
        an error "Delete files to add must be empty because there's no delete file to be rewritten",
        we split the rewrite into 2 steps, first rewrite the data files, then add the delete files.
         */
        rewriteDataFiles(transaction, removedDataFiles, addedDataFiles);
        addDeleteFiles(transaction, addedDeleteFiles);
      } else {
        rewriteFiles(
            transaction, removedDataFiles, addedDataFiles, removedDeleteFiles, addedDeleteFiles);
      }
      transaction.commitTransaction();
      LOG.info(
          "Successfully committed table {} in {} ms.",
          table.id(),
          System.currentTimeMillis() - startTime);
    } catch (Exception e) {
      LOG.warn("Failed to commit table {}.", table.id(), e);
      throw new OptimizingCommitException("unexpected commit error ", e);
    }
  }

  private void rewriteDataFiles(
      Transaction transaction, Set<DataFile> removedDataFiles, Set<DataFile> addedDataFiles) {
    rewriteFiles(
        transaction,
        removedDataFiles,
        addedDataFiles,
        Collections.emptySet(),
        Collections.emptySet());
  }

  private void rewriteFiles(
      Transaction transaction,
      Set<DataFile> removedDataFiles,
      Set<DataFile> addedDataFiles,
      Set<DeleteFile> removedDeleteFiles,
      Set<DeleteFile> addedDeleteFiles) {
    if (removedDataFiles.isEmpty()
        && addedDataFiles.isEmpty()
        && removedDeleteFiles.isEmpty()
        && addedDeleteFiles.isEmpty()) {
      return;
    }

    RewriteFiles rewriteFiles =
        transaction.newRewrite().scanManifestsWith(IcebergThreadPools.getCommitExecutor());
    if (targetSnapshotId != Constants.INVALID_SNAPSHOT_ID) {
      long sequenceNumber = table.asUnkeyedTable().snapshot(targetSnapshotId).sequenceNumber();
      rewriteFiles.validateFromSnapshot(targetSnapshotId).dataSequenceNumber(sequenceNumber);
    }
    removedDataFiles.forEach(rewriteFiles::deleteFile);
    addedDataFiles.forEach(rewriteFiles::addFile);
    removedDeleteFiles.forEach(rewriteFiles::deleteFile);
    addedDeleteFiles.forEach(rewriteFiles::addFile);
    rewriteFiles.set(SnapshotSummary.SNAPSHOT_PRODUCER, CommitMetaProducer.OPTIMIZE.name());
    rewriteFiles.commit();
  }

  private void addDeleteFiles(Transaction transaction, Set<DeleteFile> addDeleteFiles) {
    RowDelta rowDelta =
        transaction.newRowDelta().scanManifestsWith(IcebergThreadPools.getCommitExecutor());
    addDeleteFiles.forEach(rowDelta::addDeletes);
    rowDelta.set(SnapshotSummary.SNAPSHOT_PRODUCER, CommitMetaProducer.OPTIMIZE.name());
    rowDelta.commit();
  }
}
