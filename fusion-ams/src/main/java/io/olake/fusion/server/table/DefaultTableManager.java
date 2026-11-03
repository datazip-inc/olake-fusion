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

package io.olake.fusion.server.table;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.api.TableIdentifier;
import io.olake.fusion.server.FusionServiceConstants;
import io.olake.fusion.server.dashboard.model.TableOptimizingInfo;
import io.olake.fusion.server.dashboard.utils.OptimizingUtil;
import io.olake.fusion.server.optimizing.OptimizingTaskMeta;
import io.olake.fusion.server.optimizing.TaskRuntime;
import io.olake.fusion.server.persistence.PersistentBase;
import io.olake.fusion.server.persistence.TableRuntimeMeta;
import io.olake.fusion.server.persistence.mapper.OptimizerMapper;
import io.olake.fusion.server.persistence.mapper.OptimizingProcessMapper;
import io.olake.fusion.server.persistence.mapper.TableMetaMapper;
import io.olake.fusion.server.persistence.mapper.TableProcessMapper;
import io.olake.fusion.server.persistence.mapper.TableRuntimeMapper;
import io.olake.fusion.server.process.TableProcessMeta;
import io.olake.fusion.server.resource.OptimizerInstance;
import io.olake.fusion.server.utils.SnowflakeIdGenerator;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DefaultTableManager extends PersistentBase implements TableManager {

  public static final Logger LOG = LoggerFactory.getLogger(DefaultTableManager.class);

  @Override
  public List<ServerTableIdentifier> listManagedTables() {
    return getAs(TableMetaMapper.class, TableMetaMapper::selectAllTableIdentifiers);
  }

  @Override
  public ServerTableIdentifier getServerTableIdentifier(TableIdentifier id) {
    return getAs(
        TableMetaMapper.class,
        mapper ->
            mapper.selectTableIdentifier(id.getCatalog(), id.getDatabase(), id.getTableName()));
  }

  @Override
  public TableProcessMeta getTableProcessMeta(long processId) {
    return getAs(TableProcessMapper.class, mapper -> mapper.getProcessMeta(processId));
  }

  @Override
  public TableRuntimeMeta getTableRuntimeMata(ServerTableIdentifier id) {
    return getAs(TableRuntimeMapper.class, mapper -> mapper.selectRuntime(id.getId()));
  }

  @Override
  public Pair<List<TableOptimizingInfo>, Integer> queryTableOptimizingInfo(
      String optimizerGroup,
      @Nullable String fuzzyDbName,
      @Nullable String fuzzyTableName,
      @Nullable List<Integer> statusCodeFilters,
      int limit,
      int offset) {

    // page helper is 1-based
    int pageNumber = (offset / limit) + 1;
    int total = 0;
    List<TableRuntimeMeta> ret;
    try (Page<?> ignore = PageHelper.startPage(pageNumber, limit, true)) {
      ret =
          getAs(
              TableRuntimeMapper.class,
              mapper ->
                  mapper.queryForGroups(
                      optimizerGroup, fuzzyDbName, fuzzyTableName, statusCodeFilters));
      PageInfo<TableRuntimeMeta> pageInfo = new PageInfo<>(ret);
      total = (int) pageInfo.getTotal();
    }

    if (ret.isEmpty()) {
      return Pair.of(Collections.emptyList(), total);
    }

    List<Long> tableIds =
        ret.stream().map(TableRuntimeMeta::getTableId).collect(Collectors.toList());
    List<ServerTableIdentifier> identifiers =
        getAs(TableMetaMapper.class, mapper -> mapper.selectTableIdentifiers(tableIds));
    Map<Long, ServerTableIdentifier> tableId2Identifier =
        identifiers.stream()
            .collect(Collectors.toMap(ServerTableIdentifier::getId, Function.identity()));

    List<Long> processIds =
        getAs(TableProcessMapper.class, mapper -> mapper.selectTableMaxProcessIds(tableIds));

    List<OptimizingTaskMeta> taskMetas =
        getAs(
            OptimizingProcessMapper.class,
            m -> {
              if (processIds.isEmpty()) {
                return Lists.newArrayList();
              } else {
                return m.selectOptimizeTaskMetas(processIds);
              }
            });
    Map<Long, List<OptimizingTaskMeta>> tableTaskMetaMap =
        taskMetas.stream()
            .collect(Collectors.groupingBy(OptimizingTaskMeta::getTableId, Collectors.toList()));

    // load quota info
    Map<Long, List<TaskRuntime.TaskQuota>> tableQuotaMap = getQuotaTime(tableIds);
    List<OptimizerInstance> instances = getAs(OptimizerMapper.class, OptimizerMapper::selectAll);
    Map<String, Integer> optimizerThreadCountMap =
        instances == null
            ? Collections.emptyMap()
            : instances.stream()
                .collect(
                    Collectors.groupingBy(
                        OptimizerInstance::getGroupName,
                        Collectors.summingInt(OptimizerInstance::getThreadCount)));

    List<TableOptimizingInfo> infos =
        ret.stream()
            .map(
                meta -> {
                  ServerTableIdentifier identifier = tableId2Identifier.get(meta.getTableId());
                  List<OptimizingTaskMeta> tasks = tableTaskMetaMap.get(meta.getTableId());
                  List<TaskRuntime.TaskQuota> quotas = tableQuotaMap.get(meta.getTableId());
                  int threadCount = optimizerThreadCountMap.getOrDefault(meta.getGroupName(), 0);
                  return OptimizingUtil.buildTableOptimizeInfo(
                      identifier, meta, tasks, quotas, Math.max(threadCount, 1));
                })
            .collect(Collectors.toList());
    return Pair.of(infos, total);
  }

  private Map<Long, List<TaskRuntime.TaskQuota>> getQuotaTime(List<Long> tableIds) {
    if (tableIds == null || tableIds.isEmpty()) {
      return Maps.newHashMap();
    }
    long calculatingEndTime = System.currentTimeMillis();
    long calculatingStartTime = calculatingEndTime - FusionServiceConstants.QUOTA_LOOK_BACK_TIME;
    long minProcessId = SnowflakeIdGenerator.getMinSnowflakeId(calculatingStartTime);
    List<TaskRuntime.TaskQuota> quotas =
        getAs(
            OptimizingProcessMapper.class,
            mapper -> mapper.selectTableQuotas(tableIds, minProcessId));

    return quotas.stream()
        .collect(Collectors.groupingBy(TaskRuntime.TaskQuota::getTableId, Collectors.toList()));
  }
}
