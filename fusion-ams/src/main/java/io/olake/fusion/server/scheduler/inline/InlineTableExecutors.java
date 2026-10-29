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

package io.olake.fusion.server.scheduler.inline;

import io.olake.fusion.config.Configurations;
import io.olake.fusion.server.FusionManagementConf;
import io.olake.fusion.server.table.TableService;

public class InlineTableExecutors {

  private static final InlineTableExecutors instance = new InlineTableExecutors();
  private SnapshotsExpiringExecutor snapshotsExpiringExecutor;
  private TableRuntimeRefreshExecutor tableRefreshingExecutor;
  private OrphanFilesCleaningExecutor orphanFilesCleaningExecutor;
  private DanglingDeleteFilesCleaningExecutor danglingDeleteFilesCleaningExecutor;
  private BlockerExpiringExecutor blockerExpiringExecutor;
  private OptimizingCommitExecutor optimizingCommitExecutor;
  private OptimizingExpiringExecutor optimizingExpiringExecutor;
  private TagsAutoCreatingExecutor tagsAutoCreatingExecutor;
  private TableHealthScoreExecutor tableHealthScoreExecutor;
  private DataExpiringExecutor dataExpiringExecutor;

  public static InlineTableExecutors getInstance() {
    return instance;
  }

  public void setup(TableService tableService, Configurations conf) {
    if (conf.getBoolean(FusionManagementConf.EXPIRE_SNAPSHOTS_ENABLED)) {
      this.snapshotsExpiringExecutor =
          new SnapshotsExpiringExecutor(
              tableService,
              conf.getInteger(FusionManagementConf.EXPIRE_SNAPSHOTS_THREAD_COUNT),
              conf.get(FusionManagementConf.EXPIRE_SNAPSHOTS_INTERVAL));
    }
    if (conf.getBoolean(FusionManagementConf.CLEAN_ORPHAN_FILES_ENABLED)) {
      this.orphanFilesCleaningExecutor =
          new OrphanFilesCleaningExecutor(
              tableService,
              conf.getInteger(FusionManagementConf.CLEAN_ORPHAN_FILES_THREAD_COUNT),
              conf.get(FusionManagementConf.CLEAN_ORPHAN_FILES_INTERVAL));
    }
    if (conf.getBoolean(FusionManagementConf.CLEAN_DANGLING_DELETE_FILES_ENABLED)) {
      this.danglingDeleteFilesCleaningExecutor =
          new DanglingDeleteFilesCleaningExecutor(
              tableService,
              conf.getInteger(FusionManagementConf.CLEAN_DANGLING_DELETE_FILES_THREAD_COUNT),
              conf.get(FusionManagementConf.CLEAN_DANGLING_DELETE_FILES_INTERVAL));
    }
    this.optimizingCommitExecutor =
        new OptimizingCommitExecutor(
            tableService, conf.getInteger(FusionManagementConf.OPTIMIZING_COMMIT_THREAD_COUNT));
    this.optimizingExpiringExecutor =
        new OptimizingExpiringExecutor(
            tableService,
            conf.getInteger(FusionManagementConf.OPTIMIZING_RUNTIME_DATA_KEEP_DAYS),
            conf.getInteger(FusionManagementConf.OPTIMIZING_RUNTIME_DATA_EXPIRE_INTERVAL_HOURS));
    this.blockerExpiringExecutor = new BlockerExpiringExecutor(tableService);
    this.tableRefreshingExecutor =
        new TableRuntimeRefreshExecutor(
            tableService,
            conf.getInteger(FusionManagementConf.REFRESH_TABLES_THREAD_COUNT),
            conf.get(FusionManagementConf.REFRESH_TABLES_INTERVAL).toMillis());
    this.tableHealthScoreExecutor =
        new TableHealthScoreExecutor(
            tableService,
            conf.getInteger(FusionManagementConf.REFRESH_TABLES_THREAD_COUNT),
            conf.get(FusionManagementConf.REFRESH_TABLES_INTERVAL).toMillis());
    if (conf.getBoolean(FusionManagementConf.AUTO_CREATE_TAGS_ENABLED)) {
      this.tagsAutoCreatingExecutor =
          new TagsAutoCreatingExecutor(
              tableService,
              conf.getInteger(FusionManagementConf.AUTO_CREATE_TAGS_THREAD_COUNT),
              conf.get(FusionManagementConf.AUTO_CREATE_TAGS_INTERVAL).toMillis());
    }
    if (conf.getBoolean(FusionManagementConf.DATA_EXPIRATION_ENABLED)) {
      this.dataExpiringExecutor =
          new DataExpiringExecutor(
              tableService,
              conf.getInteger(FusionManagementConf.DATA_EXPIRATION_THREAD_COUNT),
              conf.get(FusionManagementConf.DATA_EXPIRATION_INTERVAL));
    }
  }

  public SnapshotsExpiringExecutor getSnapshotsExpiringExecutor() {
    return snapshotsExpiringExecutor;
  }

  public TableRuntimeRefreshExecutor getTableRefreshingExecutor() {
    return tableRefreshingExecutor;
  }

  public OrphanFilesCleaningExecutor getOrphanFilesCleaningExecutor() {
    return orphanFilesCleaningExecutor;
  }

  public DanglingDeleteFilesCleaningExecutor getDanglingDeleteFilesCleaningExecutor() {
    return danglingDeleteFilesCleaningExecutor;
  }

  public BlockerExpiringExecutor getBlockerExpiringExecutor() {
    return blockerExpiringExecutor;
  }

  public OptimizingCommitExecutor getOptimizingCommitExecutor() {
    return optimizingCommitExecutor;
  }

  public OptimizingExpiringExecutor getOptimizingExpiringExecutor() {
    return optimizingExpiringExecutor;
  }

  public TagsAutoCreatingExecutor getTagsAutoCreatingExecutor() {
    return tagsAutoCreatingExecutor;
  }

  public TableHealthScoreExecutor getTableHealthScoreExecutor() {
    return tableHealthScoreExecutor;
  }

  public DataExpiringExecutor getDataExpiringExecutor() {
    return dataExpiringExecutor;
  }
}
