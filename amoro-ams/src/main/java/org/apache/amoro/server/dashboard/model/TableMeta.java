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

package org.apache.amoro.server.dashboard.model;

import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.amoro.table.TableProperties;

import java.util.Objects;

public class TableMeta {
  public String name;
  public String type;
  public Integer healthScore = -1;

  @JsonProperty("enabled")
  public Boolean optimizingEnabled;

  @JsonProperty(TableProperties.OLAKE_CREATED)
  public Boolean olakeCreated = false;

  @JsonProperty("lite")
  public CompactionInfo lastMinorCompaction;

  @JsonProperty("medium")
  public CompactionInfo lastMajorCompaction;

  @JsonProperty("full")
  public CompactionInfo lastFullCompaction;

  public static class CompactionInfo {
    @JsonProperty("run_id")
    private Long processId;

    @JsonProperty("finish_time")
    private Long finishTime;

    @JsonProperty("status")
    private String status;

    public CompactionInfo(Long processId, Long finishTime, String status) {
      this.processId = processId;
      this.finishTime = finishTime;
      this.status = status;
    }
  }

  public TableMeta(String name, String type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public void setHealthScore(Integer healthScore) {
    this.healthScore = healthScore;
  }

  public void setOptimizingEnabled(Boolean optimizingEnabled) {
    this.optimizingEnabled = optimizingEnabled;
  }

  public void setOlakeCreated(Boolean olakeCreated) {
    this.olakeCreated = olakeCreated;
  }

  public void setLastMinorCompaction(CompactionInfo lastMinorCompaction) {
    this.lastMinorCompaction = lastMinorCompaction;
  }

  public void setLastMajorCompaction(CompactionInfo lastMajorCompaction) {
    this.lastMajorCompaction = lastMajorCompaction;
  }

  public void setLastFullCompaction(CompactionInfo lastFullCompaction) {
    this.lastFullCompaction = lastFullCompaction;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TableMeta tableMeta = (TableMeta) o;
    return Objects.equals(name, tableMeta.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name);
  }

  public enum TableType {
    ARCTIC("arctic"),
    HIVE("hive"),
    ICEBERG("iceberg"),
    PAIMON("paimon"),

    HUDI("hudi");

    private final String name;

    TableType(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }
}
