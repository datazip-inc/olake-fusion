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

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Key value store for long lived platform level properties. The statements stay dialect neutral, so
 * an upsert is expressed as insert / update rather than with vendor specific syntax.
 */
public interface PlatformPropertyMapper {
  String TABLE_NAME = "platform_property";

  @Select("SELECT property_value FROM " + TABLE_NAME + " WHERE property_key = #{key}")
  String getProperty(@Param("key") String key);

  @Insert("INSERT INTO " + TABLE_NAME + "(property_key, property_value) VALUES(#{key}, #{value})")
  void insertProperty(@Param("key") String key, @Param("value") String value);

  @Update("UPDATE " + TABLE_NAME + " SET property_value = #{value} WHERE property_key = #{key}")
  int updateProperty(@Param("key") String key, @Param("value") String value);

  @Delete("DELETE FROM " + TABLE_NAME + " WHERE property_key = #{key}")
  void deleteProperty(@Param("key") String key);
}
