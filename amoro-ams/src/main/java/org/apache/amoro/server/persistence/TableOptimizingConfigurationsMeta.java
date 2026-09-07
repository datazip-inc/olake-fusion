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

package org.apache.amoro.server.persistence;

/** Per-table state stored in and owned by the AMS db */
public class TableOptimizingConfigurationsMeta {

  public static final long DEFAULT_TARGET_SIZE = 512L * 1024 * 1024;

  private String catalogName;
  private String dbName;
  private String tableName;
  private boolean selfOptimizingEnabled;
  private String minorTriggerCron;
  private String majorTriggerCron;
  private String fullTriggerCron;
  private long targetSize = DEFAULT_TARGET_SIZE;
  private Boolean olakeCreated;
  private Integer healthScore;

  public TableOptimizingConfigurationsMeta() {}

  public TableOptimizingConfigurationsMeta(String catalogName, String dbName, String tableName) {
    this.catalogName = catalogName;
    this.dbName = dbName;
    this.tableName = tableName;
  }

  public String getCatalogName() {
    return catalogName;
  }

  public void setCatalogName(String catalogName) {
    this.catalogName = catalogName;
  }

  public String getDbName() {
    return dbName;
  }

  public void setDbName(String dbName) {
    this.dbName = dbName;
  }

  public String getTableName() {
    return tableName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName;
  }

  public boolean getSelfOptimizingEnabled() {
    return selfOptimizingEnabled;
  }

  public void setSelfOptimizingEnabled(boolean selfOptimizingEnabled) {
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

  public long getTargetSize() {
    return targetSize;
  }

  public void setTargetSize(long targetSize) {
    this.targetSize = targetSize;
  }

  public Boolean getOlakeCreated() {
    return olakeCreated;
  }

  public void setOlakeCreated(Boolean olakeCreated) {
    this.olakeCreated = olakeCreated;
  }

  public Integer getHealthScore() {
    return healthScore;
  }

  public void setHealthScore(Integer healthScore) {
    this.healthScore = healthScore;
  }

  @Override
  public String toString() {
    return "TableOptimizingConfigurationsMeta{"
        + "catalogName='"
        + catalogName
        + "', dbName='"
        + dbName
        + "', tableName='"
        + tableName
        + "', selfOptimizingEnabled="
        + selfOptimizingEnabled
        + ", minorTriggerCron='"
        + minorTriggerCron
        + "', majorTriggerCron='"
        + majorTriggerCron
        + "', fullTriggerCron='"
        + fullTriggerCron
        + "', targetSize="
        + targetSize
        + ", olakeCreated="
        + olakeCreated
        + ", healthScore="
        + healthScore
        + '}';
  }
}
