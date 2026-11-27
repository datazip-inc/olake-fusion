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

package io.olake.fusion.utils;

import io.olake.fusion.table.TableProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.data.GenericRecord;

import java.util.Map;

/** Utils to handle table properties. */
public class TablePropertyUtil {

  public static final StructLike EMPTY_STRUCT = GenericRecord.create(new Schema());

  /**
   * Value of the {@link TableProperties#TABLE_FORMAT} property that Amoro wrote on the base and
   * change stores of a legacy mixed-iceberg table. Mixed-iceberg tables are not supported; the
   * value is only used to recognise and skip such stores.
   */
  public static final String LEGACY_MIXED_ICEBERG_FORMAT = "MIXED_ICEBERG";

  public static long getTableWatermark(Map<String, String> properties) {
    String watermarkValue = properties.get(TableProperties.WATERMARK_TABLE);
    if (watermarkValue == null) {
      return -1;
    } else {
      return Long.parseLong(watermarkValue);
    }
  }

  /**
   * Check if an iceberg table is a table store of a mixed-iceberg table.
   *
   * @param properties table properties of iceberg
   * @return true if this it a table store.
   */
  public static boolean isMixedTableStore(Map<String, String> properties) {
    String format = properties.get(TableProperties.TABLE_FORMAT);
    String tableStore = properties.get(TableProperties.MIXED_FORMAT_TABLE_STORE);
    boolean baseStore = TableProperties.MIXED_FORMAT_TABLE_STORE_BASE.equalsIgnoreCase(tableStore);
    boolean changeStore =
        TableProperties.MIXED_FORMAT_TABLE_STORE_CHANGE.equalsIgnoreCase(tableStore);
    return LEGACY_MIXED_ICEBERG_FORMAT.equalsIgnoreCase(format) && (baseStore || changeStore);
  }
}
