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

import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body updating the optimizing configurations of iceberg tables, in the format olake-ui
 * sends. Only the configurations provided are updated.
 */
public class OptimizingConfigurations {
  private static final long BYTES_IN_MB = 1024L * 1024;

  private List<String> tables;

  @JsonProperty("sql_input")
  private Values values = new Values();

  public List<String> getTables() {
    return tables;
  }

  /** Sets the configurations provided on the stored ones of a table. */
  public void applyTo(TableOptimizingConfigurationsMeta meta) {
    if (values.enabled != null) {
      meta.setSelfOptimizingEnabled(values.enabled);
    }
    if (values.minorCron != null) {
      meta.setMinorTriggerCron(values.minorCron);
    }
    if (values.majorCron != null) {
      meta.setMajorTriggerCron(values.majorCron);
    }
    if (values.fullCron != null) {
      meta.setFullTriggerCron(values.fullCron);
    }
    if (values.targetSizeMb != null) {
      meta.setTargetSize(values.targetSizeMb * BYTES_IN_MB);
    }
  }

  private static class Values {
    @JsonProperty("enabled_for_optimization")
    private Boolean enabled;

    @JsonProperty("minor_cron")
    private String minorCron;

    @JsonProperty("major_cron")
    private String majorCron;

    @JsonProperty("full_cron")
    private String fullCron;

    @JsonProperty("target_file_size")
    private Long targetSizeMb;
  }
}
