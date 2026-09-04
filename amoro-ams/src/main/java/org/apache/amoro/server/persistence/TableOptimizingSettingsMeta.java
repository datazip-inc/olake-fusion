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

/**
 * Self-optimizing settings stored in and owned by the AMS database, one row per table, used when
 * transferring data from/to the {@code table_optimizing_settings} table.
 *
 * <p>Every setting field is boxed on purpose: {@code null} means "not overridden here", so the
 * value falls back to the table properties, the catalog defaults and finally the hard coded
 * defaults in {@link org.apache.amoro.table.TableProperties}.
 */
public class TableOptimizingSettingsMeta {

  private String catalogName;
  private String dbName;
  private String tableName;
  private Boolean selfOptimizingEnabled;
  private String minorTriggerCron;
  private String majorTriggerCron;
  private String fullTriggerCron;
  private Long targetSize;

  public TableOptimizingSettingsMeta() {}

  public TableOptimizingSettingsMeta(String catalogName, String dbName, String tableName) {
    this.catalogName = catalogName;
    this.dbName = dbName;
    this.tableName = tableName;
  }

  /** Returns true when no setting is actually overridden, so the row carries no information. */
  public boolean isEmpty() {
    return selfOptimizingEnabled == null
        && minorTriggerCron == null
        && majorTriggerCron == null
        && fullTriggerCron == null
        && targetSize == null;
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

  @Override
  public String toString() {
    return "TableOptimizingSettingsMeta{"
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
        + '}';
  }
}
