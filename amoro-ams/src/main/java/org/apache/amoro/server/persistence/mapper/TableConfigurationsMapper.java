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

package org.apache.amoro.server.persistence.mapper;

import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

public interface TableConfigurationsMapper {
  String TABLE_NAME = "table_configurations";

  String SELECT_COLS =
      " catalog_name, db_name, table_name, self_optimizing_enabled, "
          + " minor_trigger_cron, major_trigger_cron, full_trigger_cron, target_size, "
          + " olake_created, health_score, health_score_snapshot_id ";

  /* ---------- select ---------- */

  @Select(
      "SELECT "
          + SELECT_COLS
          + "FROM "
          + TABLE_NAME
          + " WHERE catalog_name = #{catalogName} AND db_name = #{dbName} "
          + "   AND table_name = #{tableName}")
  @Results(
      value = {
        @Result(column = "catalog_name", property = "catalogName"),
        @Result(column = "db_name", property = "dbName"),
        @Result(column = "table_name", property = "tableName"),
        @Result(
            column = "self_optimizing_enabled",
            property = "selfOptimizingEnabled",
            jdbcType = JdbcType.BOOLEAN),
        @Result(column = "minor_trigger_cron", property = "minorTriggerCron"),
        @Result(column = "major_trigger_cron", property = "majorTriggerCron"),
        @Result(column = "full_trigger_cron", property = "fullTriggerCron"),
        @Result(column = "target_size", property = "targetSize", jdbcType = JdbcType.BIGINT),
        @Result(column = "olake_created", property = "olakeCreated", jdbcType = JdbcType.BOOLEAN),
        @Result(column = "health_score", property = "healthScore", jdbcType = JdbcType.INTEGER),
        @Result(
            column = "health_score_snapshot_id",
            property = "healthScoreSnapshotId",
            jdbcType = JdbcType.BIGINT)
      })
  TableOptimizingConfigurationsMeta selectScope(
      @Param("catalogName") String catalogName,
      @Param("dbName") String dbName,
      @Param("tableName") String tableName);

  /* ---------- update ---------- */

  @Update(
      "UPDATE "
          + TABLE_NAME
          + " SET self_optimizing_enabled = #{selfOptimizingEnabled, jdbcType=BOOLEAN}, "
          + "     minor_trigger_cron      = #{minorTriggerCron, jdbcType=VARCHAR}, "
          + "     major_trigger_cron      = #{majorTriggerCron, jdbcType=VARCHAR}, "
          + "     full_trigger_cron       = #{fullTriggerCron, jdbcType=VARCHAR}, "
          + "     target_size             = #{targetSize, jdbcType=BIGINT}, "
          + "     olake_created           = #{olakeCreated, jdbcType=BOOLEAN}, "
          + "     health_score            = #{healthScore, jdbcType=INTEGER}, "
          + "     health_score_snapshot_id = #{healthScoreSnapshotId, jdbcType=BIGINT}, "
          + "     update_time             = CURRENT_TIMESTAMP "
          + " WHERE catalog_name = #{catalogName} AND db_name = #{dbName} "
          + "   AND table_name = #{tableName}")
  int updateSettings(TableOptimizingConfigurationsMeta meta);

  /* ---------- insert ---------- */

  @Insert(
      "INSERT INTO "
          + TABLE_NAME
          + " (catalog_name, db_name, table_name, self_optimizing_enabled, "
          + "  minor_trigger_cron, major_trigger_cron, full_trigger_cron, target_size, "
          + "  olake_created, health_score, health_score_snapshot_id) "
          + "VALUES (#{catalogName}, #{dbName}, #{tableName}, "
          + "        #{selfOptimizingEnabled, jdbcType=BOOLEAN}, "
          + "        #{minorTriggerCron, jdbcType=VARCHAR}, "
          + "        #{majorTriggerCron, jdbcType=VARCHAR}, "
          + "        #{fullTriggerCron, jdbcType=VARCHAR}, "
          + "        #{targetSize, jdbcType=BIGINT}, "
          + "        #{olakeCreated, jdbcType=BOOLEAN}, "
          + "        #{healthScore, jdbcType=INTEGER}, "
          + "        #{healthScoreSnapshotId, jdbcType=BIGINT})")
  int insertSettings(TableOptimizingConfigurationsMeta meta);

  /* ---------- delete ---------- */

  @Delete("DELETE FROM " + TABLE_NAME + " WHERE catalog_name = #{catalogName}")
  int deleteAllTablesOfCatalog(@Param("catalogName") String catalogName);
}
