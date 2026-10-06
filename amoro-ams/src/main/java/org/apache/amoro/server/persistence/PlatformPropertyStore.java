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

import org.apache.amoro.exception.PersistenceException;
import org.apache.amoro.server.persistence.mapper.PlatformPropertyMapper;

/**
 * Access to {@code platform_property}, a small key value table for state that must outlive a single
 * AMS process and be shared by every replica, such as the OLake telemetry install id.
 */
public class PlatformPropertyStore extends PersistentBase {

  /** The anonymous OLake install id, owned by the OLake UI and pushed to AMS on startup. */
  public static final String TELEMETRY_INSTALL_ID = "telemetry.install_id";

  public String get(String key) {
    return getAs(PlatformPropertyMapper.class, mapper -> mapper.getProperty(key));
  }

  /** Stores a value, overwriting whatever is there. */
  public void put(String key, String value) {
    long updated =
        updateAs(PlatformPropertyMapper.class, mapper -> mapper.updateProperty(key, value));
    if (updated > 0) {
      return;
    }
    try {
      doAs(PlatformPropertyMapper.class, mapper -> mapper.insertProperty(key, value));
    } catch (PersistenceException e) {
      // Another AMS replica inserted the same key first; its row is the one to overwrite.
      updateAs(PlatformPropertyMapper.class, mapper -> mapper.updateProperty(key, value));
    }
  }
}
