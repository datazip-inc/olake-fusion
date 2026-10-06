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

  /**
   * Stores a value, overwriting whatever is there. Replicas race only on first boot, where the
   * loser's insert fails on the primary key and is retried as an update.
   */
  public void put(String key, String value) {
    if (get(key) != null) {
      doAs(PlatformPropertyMapper.class, mapper -> mapper.updateProperty(key, value));
      return;
    }
    try {
      doAs(PlatformPropertyMapper.class, mapper -> mapper.insertProperty(key, value));
    } catch (PersistenceException e) {
      // Another AMS replica inserted the same key between the read and the insert.
      doAs(PlatformPropertyMapper.class, mapper -> mapper.updateProperty(key, value));
    }
  }

  /** Stores the value only when the key is still unset, and returns the effective value. */
  public String putIfAbsent(String key, String value) {
    String existing = get(key);
    if (existing != null && !existing.isEmpty()) {
      return existing;
    }
    try {
      doAs(PlatformPropertyMapper.class, mapper -> mapper.insertProperty(key, value));
      return value;
    } catch (PersistenceException e) {
      String stored = get(key);
      return stored != null && !stored.isEmpty() ? stored : value;
    }
  }
}
