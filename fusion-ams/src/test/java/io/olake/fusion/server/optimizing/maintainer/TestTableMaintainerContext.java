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

import io.olake.fusion.config.TableConfiguration;
import io.olake.fusion.maintainer.MaintainerMetrics;
import io.olake.fusion.maintainer.OptimizingInfo;
import io.olake.fusion.maintainer.TableMaintainerContext;
import io.olake.fusion.server.table.TableConfigurations;
import io.olake.fusion.table.TableProperties;
import org.apache.iceberg.Table;

import java.util.HashMap;
import java.util.Map;

/** Utility class for creating test TableMaintainerContext instances. */
public class TestTableMaintainerContext {

  public static TableConfiguration parseTestTableConfig(Map<String, String> properties) {
    Map<String, String> merged = new HashMap<>(properties);
    merged.putIfAbsent(TableProperties.ENABLE_TABLE_EXPIRE, "true");
    return TableConfigurations.parseTableConfig(merged);
  }

  /** Create a test TableMaintainerContext for the given Table. */
  public static TableMaintainerContext of(Table table) {
    return new Impl(parseTestTableConfig(table.properties()));
  }

  /** Test implementation of TableMaintainerContext. */
  public static class Impl implements TableMaintainerContext {
    private final TableConfiguration tableConfiguration;
    private OptimizingInfo optimizingInfo;

    public Impl(TableConfiguration tableConfiguration) {
      this.tableConfiguration = tableConfiguration;
      this.optimizingInfo = OptimizingInfo.EMPTY;
    }

    public void setOptimizingInfo(OptimizingInfo optimizingInfo) {
      this.optimizingInfo = optimizingInfo;
    }

    @Override
    public TableConfiguration getTableConfiguration() {
      return tableConfiguration;
    }

    @Override
    public MaintainerMetrics getMetrics() {
      return MaintainerMetrics.NOOP;
    }

    @Override
    public OptimizingInfo getOptimizingInfo() {
      return optimizingInfo;
    }
  }
}
