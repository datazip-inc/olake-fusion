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

package org.apache.amoro.server.table;

import org.apache.amoro.config.TableConfiguration;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/** Snapshot expiry properties as parsed into {@link TableConfiguration}. */
public class TestTableConfigurationsSnapshotExpire {

  private static TableConfiguration parse(Map<String, String> properties) {
    return TableConfigurations.parseTableConfig(properties);
  }

  @Test
  public void gateNeedsSnapshotExpireEnabledAndGc() {
    Assert.assertTrue(
        parse(ImmutableMap.of("snapshot-expire.enabled", "true")).isExpireSnapshotEnabled());
    Assert.assertFalse(
        parse(ImmutableMap.of("snapshot-expire.enabled", "true", "gc.enabled", "false"))
            .isExpireSnapshotEnabled());
    Assert.assertFalse(
        parse(ImmutableMap.of("snapshot-expire.enabled", "false")).isExpireSnapshotEnabled());
    Assert.assertFalse(
        "table-expire.enabled is no longer read for Iceberg tables",
        parse(ImmutableMap.of("table-expire.enabled", "true")).isExpireSnapshotEnabled());
  }

  @Test
  public void cronAloneEnablesExpiry() {
    Assert.assertTrue(
        parse(ImmutableMap.of("snapshot-expire.trigger.cron", "0 0 * * *"))
            .isExpireSnapshotEnabled());
    Assert.assertFalse(
        "an explicit false wins over the cron",
        parse(
                ImmutableMap.of(
                    "snapshot-expire.trigger.cron",
                    "0 0 * * *",
                    "snapshot-expire.enabled",
                    "false"))
            .isExpireSnapshotEnabled());
    Assert.assertFalse(
        parse(ImmutableMap.of("snapshot-expire.trigger.cron", "0 0 * * *", "gc.enabled", "false"))
            .isExpireSnapshotEnabled());
    Assert.assertFalse(
        "an empty cron is Never",
        parse(ImmutableMap.of("snapshot-expire.trigger.cron", " ")).isExpireSnapshotEnabled());
    Assert.assertFalse(parse(ImmutableMap.of()).isExpireSnapshotEnabled());
  }

  @Test
  public void durationWithoutUnitIsDays() {
    Assert.assertEquals(
        3 * 24 * 60L,
        parse(ImmutableMap.of("snapshot.keep.duration", "3")).getSnapshotTTLMinutes());
    Assert.assertEquals(
        90L, parse(ImmutableMap.of("snapshot.keep.duration", "90min")).getSnapshotTTLMinutes());
    Assert.assertNull(
        parse(ImmutableMap.of("snapshot.keep.duration", "3")).getSnapshotExpireConfigError());
  }

  @Test
  public void readsExpiryCron() {
    Assert.assertEquals(
        "*/2 * * * *",
        parse(ImmutableMap.of("snapshot-expire.trigger.cron", "*/2 * * * *"))
            .getSnapshotExpireTriggerCron());
    Assert.assertNull(parse(ImmutableMap.of()).getSnapshotExpireTriggerCron());
  }

  @Test
  public void cronChangeIsAConfigChange() {
    TableConfiguration everyTwoMinutes =
        parse(
            ImmutableMap.of(
                "snapshot-expire.enabled", "true", "snapshot-expire.trigger.cron", "*/2 * * * *"));
    TableConfiguration daily =
        parse(
            ImmutableMap.of(
                "snapshot-expire.enabled", "true", "snapshot-expire.trigger.cron", "0 0 * * *"));
    Assert.assertNotEquals(everyTwoMinutes, daily);
  }
}
