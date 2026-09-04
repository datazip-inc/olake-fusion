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

import org.apache.amoro.server.persistence.TableOptimizingSettingsMeta;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;

/**
 * Reads and writes the self-optimizing settings owned by AMS, one row per table.
 *
 * <p>Every setting column is nullable and {@code null} means "not overridden", so nulls are always
 * written explicitly with a {@code jdbcType} rather than skipped.
 */
public interface TableOptimizingSettingsMapper {
  String TABLE_NAME = "table_optimizing_settings";

  String SELECT_COLS =
      " catalog_name, db_name, table_name, self_optimizing_enabled, "
          + " minor_trigger_cron, major_trigger_cron, full_trigger_cron, target_size ";

  /* ---------- select ---------- */

  @Select("SELECT " + SELECT_COLS + "FROM " + TABLE_NAME)
  @Results(
      id = "tableOptimizingSettings",
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
        @Result(column = "target_size", property = "targetSize", jdbcType = JdbcType.BIGINT)
      })
  List<TableOptimizingSettingsMeta> selectAll();

  /* ---------- upsert ----------
   *
   * Written as update-then-insert so the same statements work on Postgres and on the embedded
   * Derby that backs the default deployment and the tests.
   * TODO: collapse into a single native upsert per dialect once the write rate makes the extra
   *   round trip matter, and when MySQL is supported. Branch on _databaseId inside a <script>,
   *   the way TableRuntimeMapper.queryForGroups does, with:
   *     postgres: INSERT ... ON CONFLICT (catalog_name, db_name, table_name) DO UPDATE SET ...
   *     derby:    MERGE INTO table_optimizing_settings USING SYSIBM.SYSDUMMY1 ON ... WHEN MATCHED
   *   See HaLeaseMapper.upsertServerInfo for the trap this pattern avoids: that statement is
   *   MySQL-only and silently fails everywhere else.
   */

  /**
   * Updates an existing scope row. Every column is overwritten, nulls included, so clearing an
   * override is a plain update.
   *
   * @return affected rows, 0 when the scope has no row yet
   */
  @Update(
      "UPDATE "
          + TABLE_NAME
          + " SET self_optimizing_enabled = #{selfOptimizingEnabled, jdbcType=BOOLEAN}, "
          + "     minor_trigger_cron      = #{minorTriggerCron, jdbcType=VARCHAR}, "
          + "     major_trigger_cron      = #{majorTriggerCron, jdbcType=VARCHAR}, "
          + "     full_trigger_cron       = #{fullTriggerCron, jdbcType=VARCHAR}, "
          + "     target_size             = #{targetSize, jdbcType=BIGINT}, "
          + "     update_time             = CURRENT_TIMESTAMP "
          + " WHERE catalog_name = #{catalogName} AND db_name = #{dbName} "
          + "   AND table_name = #{tableName}")
  int updateSettings(TableOptimizingSettingsMeta meta);

  /**
   * Inserts a scope row. Callers must run {@link #updateSettings} first and only insert when it
   * affected no rows; the unique index on the scope makes a concurrent double insert fail rather
   * than duplicate.
   */
  @Insert(
      "INSERT INTO "
          + TABLE_NAME
          + " (catalog_name, db_name, table_name, self_optimizing_enabled, "
          + "  minor_trigger_cron, major_trigger_cron, full_trigger_cron, target_size) "
          + "VALUES (#{catalogName}, #{dbName}, #{tableName}, "
          + "        #{selfOptimizingEnabled, jdbcType=BOOLEAN}, "
          + "        #{minorTriggerCron, jdbcType=VARCHAR}, "
          + "        #{majorTriggerCron, jdbcType=VARCHAR}, "
          + "        #{fullTriggerCron, jdbcType=VARCHAR}, "
          + "        #{targetSize, jdbcType=BIGINT})")
  int insertSettings(TableOptimizingSettingsMeta meta);

  /* ---------- delete ---------- */

  @Delete(
      "DELETE FROM "
          + TABLE_NAME
          + " WHERE catalog_name = #{catalogName} AND db_name = #{dbName} "
          + "   AND table_name = #{tableName}")
  int deleteSettings(
      @Param("catalogName") String catalogName,
      @Param("dbName") String dbName,
      @Param("tableName") String tableName);
}
