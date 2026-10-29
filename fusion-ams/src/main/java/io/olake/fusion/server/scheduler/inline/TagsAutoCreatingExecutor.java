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

import io.olake.fusion.FusionTable;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableRuntime;
import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.maintainer.TableMaintainer;
import io.olake.fusion.server.optimizing.maintainer.TableMaintainers;
import io.olake.fusion.server.scheduler.PeriodicTableScheduler;
import io.olake.fusion.server.table.TableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/** Service for automatically creating tags for table periodically. */
public class TagsAutoCreatingExecutor extends PeriodicTableScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(TagsAutoCreatingExecutor.class);

  private final long interval;

  protected TagsAutoCreatingExecutor(TableService tableService, int poolSize, long interval) {
    super(tableService, poolSize);
    this.interval = interval;
  }

  @Override
  protected long getNextExecutingTime(TableRuntime tableRuntime) {
    return interval;
  }

  @Override
  protected boolean enabled(TableRuntime tableRuntime) {
    return tableRuntime.getTableConfiguration().getTagConfiguration().isAutoCreateTag()
        && tableRuntime.getFormat() == TableFormat.ICEBERG;
  }

  @Override
  protected long getExecutorDelay() {
    return ThreadLocalRandom.current().nextLong(interval);
  }

  @Override
  protected void execute(TableRuntime tableRuntime) {
    try {
      FusionTable<?> amoroTable = loadTable(tableRuntime);
      TableMaintainer tableMaintainer = TableMaintainers.create(amoroTable, tableRuntime);
      tableMaintainer.autoCreateTags();
    } catch (Throwable t) {
      LOG.error("Failed to create tags on {}", tableRuntime.getTableIdentifier(), t);
    }
  }

  @Override
  public void handleConfigChanged(TableRuntime tableRuntime, TableConfiguration originalConfig) {
    scheduleIfNecessary(tableRuntime, getStartDelay());
  }
}
