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

package org.apache.amoro.server.table;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.process.ProcessStatus;
import org.apache.amoro.server.dashboard.model.SnapshotExpiryProcessInfo;
import org.apache.amoro.server.dashboard.response.PageResult;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.SnapshotExpiryProcessMeta;
import org.apache.amoro.server.persistence.mapper.SnapshotExpiryProcessMapper;
import org.apache.amoro.server.persistence.mapper.TableMetaMapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Reads a table's snapshot expiry runs for the REST API. */
public class SnapshotExpiryHistory extends PersistentBase {

  /** The table's runs, newest first; {@code page} is 1-based. */
  public PageResult<SnapshotExpiryProcessInfo> list(
      String catalog, String database, String table, ProcessStatus status, int page, int pageSize) {
    ServerTableIdentifier identifier =
        getAs(TableMetaMapper.class, m -> m.selectTableIdentifier(catalog, database, table));
    if (identifier == null) {
      return PageResult.of(Collections.emptyList(), 0);
    }
    List<SnapshotExpiryProcessMeta> runs;
    long total;
    try (Page<?> ignored = PageHelper.startPage(page, pageSize, true)) {
      runs = getAs(SnapshotExpiryProcessMapper.class, m -> m.list(identifier.getId(), status));
      total = new PageInfo<>(runs).getTotal();
    }
    long now = System.currentTimeMillis();
    return PageResult.of(
        runs.stream()
            .map(run -> SnapshotExpiryProcessInfo.of(run, now))
            .collect(Collectors.toList()),
        (int) total);
  }
}
