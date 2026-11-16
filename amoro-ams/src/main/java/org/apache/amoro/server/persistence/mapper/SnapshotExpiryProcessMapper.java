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

import org.apache.amoro.process.ProcessStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/** Snapshot expiry runs, one row per run in {@code snapshot_expiry_process}. */
public interface SnapshotExpiryProcessMapper {

  /** Process type of an expiry run in the optimizing-processes API. */
  String PROCESS_TYPE = "EXPIRY";

  @Insert(
      "INSERT INTO snapshot_expiry_process "
          + "(process_id, table_id, status, retry_number, create_time, finish_time, fail_message,"
          + " summary) VALUES (#{processId}, #{tableId}, #{status}, #{retryNumber}, "
          + "#{createTime, typeHandler=org.apache.amoro.server.persistence.converter.Long2TsConverter}, "
          + "#{finishTime, typeHandler=org.apache.amoro.server.persistence.converter.Long2TsConverter}, "
          + "#{failMessage, jdbcType=VARCHAR}, "
          + "#{summary, typeHandler=org.apache.amoro.server.persistence.converter.Map2StringConverter})")
  void insert(
      @Param("processId") long processId,
      @Param("tableId") long tableId,
      @Param("status") ProcessStatus status,
      @Param("retryNumber") int retryNumber,
      @Param("createTime") long createTime,
      @Param("finishTime") long finishTime,
      @Param("failMessage") String failMessage,
      @Param("summary") Map<String, String> summary);

  @Update(
      "UPDATE snapshot_expiry_process SET status = #{status}, retry_number = #{retryNumber}, "
          + "finish_time = #{finishTime, typeHandler=org.apache.amoro.server.persistence.converter.Long2TsConverter}, "
          + "fail_message = #{failMessage, jdbcType=VARCHAR}, "
          + "summary = #{summary, typeHandler=org.apache.amoro.server.persistence.converter.Map2StringConverter} "
          + "WHERE process_id = #{processId}")
  void update(
      @Param("processId") long processId,
      @Param("status") ProcessStatus status,
      @Param("retryNumber") int retryNumber,
      @Param("finishTime") long finishTime,
      @Param("failMessage") String failMessage,
      @Param("summary") Map<String, String> summary);

  /** Marks every RUNNING row FAILED, or only the table's rows when {@code tableId} is not null. */
  @Update(
      "<script>UPDATE snapshot_expiry_process SET status = 'FAILED', fail_message = #{failMessage}, "
          + "finish_time = #{finishTime, typeHandler=org.apache.amoro.server.persistence.converter.Long2TsConverter} "
          + "WHERE status = 'RUNNING'<if test='tableId != null'> AND table_id = #{tableId}</if>"
          + "</script>")
  int failRunning(
      @Param("tableId") Long tableId,
      @Param("failMessage") String failMessage,
      @Param("finishTime") long finishTime);

  @Delete(
      "DELETE FROM snapshot_expiry_process WHERE process_id <= #{processId} AND table_id = #{tableId}")
  void deleteBefore(@Param("tableId") long tableId, @Param("processId") long processId);
}
