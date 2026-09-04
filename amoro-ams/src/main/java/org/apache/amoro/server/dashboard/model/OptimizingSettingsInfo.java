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

import java.util.List;

/**
 * Request body for updating the self-optimizing settings AMS owns, applied to every table in {@link
 * #tables}.
 *
 * <p>Only the settings present in the body are written; anything left out keeps its current value.
 */
public class OptimizingSettingsInfo {

  /** Names of the tables to apply the settings to, within the catalog and database in the path. */
  private List<String> tables;

  private Boolean selfOptimizingEnabled;
  private String minorTriggerCron;
  private String majorTriggerCron;
  private String fullTriggerCron;
  private Long targetSize;

  public List<String> getTables() {
    return tables;
  }

  public void setTables(List<String> tables) {
    this.tables = tables;
  }

  public Boolean getSelfOptimizingEnabled() {
    return selfOptimizingEnabled;
  }

  public void setSelfOptimizingEnabled(Boolean selfOptimizingEnabled) {
    this.selfOptimizingEnabled = selfOptimizingEnabled;
  }

  public String getMinorTriggerCron() {
    return minorTriggerCron;
  }

  public void setMinorTriggerCron(String minorTriggerCron) {
    this.minorTriggerCron = minorTriggerCron;
  }

  public String getMajorTriggerCron() {
    return majorTriggerCron;
  }

  public void setMajorTriggerCron(String majorTriggerCron) {
    this.majorTriggerCron = majorTriggerCron;
  }

  public String getFullTriggerCron() {
    return fullTriggerCron;
  }

  public void setFullTriggerCron(String fullTriggerCron) {
    this.fullTriggerCron = fullTriggerCron;
  }

  public Long getTargetSize() {
    return targetSize;
  }

  public void setTargetSize(Long targetSize) {
    this.targetSize = targetSize;
  }
}
