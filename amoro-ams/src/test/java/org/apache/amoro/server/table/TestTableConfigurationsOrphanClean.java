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

/** Orphan files cleaning properties as parsed into {@link TableConfiguration}. */
public class TestTableConfigurationsOrphanClean {

  private static TableConfiguration parse(Map<String, String> properties) {
    return TableConfigurations.parseTableConfig(properties);
  }

  @Test
  public void gateNeedsCleanOrphanEnabledAndGc() {
    Assert.assertTrue(
        parse(ImmutableMap.of("clean-orphan-files.enabled", "true")).isCleanOrphanEnabled());
    Assert.assertTrue(
        parse(ImmutableMap.of("clean-orphan-file.enabled", "true")).isCleanOrphanEnabled());
    Assert.assertFalse(
        parse(ImmutableMap.of("clean-orphan-files.enabled", "true", "gc.enabled", "false"))
            .isCleanOrphanEnabled());
    Assert.assertFalse(
        parse(ImmutableMap.of("clean-orphan-files.enabled", "false")).isCleanOrphanEnabled());
    Assert.assertFalse(
        parse(ImmutableMap.of("clean-orphan-file.enabled", "false")).isCleanOrphanEnabled());
  }

  @Test
  public void cronAloneEnablesCleaning() {
    Assert.assertTrue(
        parse(ImmutableMap.of("clean-orphan-files.trigger.cron", "0 0 * * *"))
            .isCleanOrphanEnabled());
    Assert.assertTrue(
        parse(ImmutableMap.of("clean-orphan-file.trigger.cron", "0 0 * * *"))
            .isCleanOrphanEnabled());
    Assert.assertFalse(
        "an explicit false wins over the cron",
        parse(
                ImmutableMap.of(
                    "clean-orphan-files.trigger.cron",
                    "0 0 * * *",
                    "clean-orphan-files.enabled",
                    "false"))
            .isCleanOrphanEnabled());
    Assert.assertFalse(
        parse(
                ImmutableMap.of(
                    "clean-orphan-files.trigger.cron",
                    "0 0 * * *",
                    "gc.enabled",
                    "false"))
            .isCleanOrphanEnabled());
    Assert.assertFalse(
        "an empty cron is Never",
        parse(ImmutableMap.of("clean-orphan-files.trigger.cron", " ")).isCleanOrphanEnabled());
    Assert.assertFalse(parse(ImmutableMap.of()).isCleanOrphanEnabled());
  }

  @Test
  public void readsCleaningCron() {
    Assert.assertEquals(
        "*/2 * * * *",
        parse(ImmutableMap.of("clean-orphan-files.trigger.cron", "*/2 * * * *"))
            .getCleanOrphanTriggerCron());
    Assert.assertEquals(
        "0 3 * * *",
        parse(ImmutableMap.of("clean-orphan-file.trigger.cron", "0 3 * * *"))
            .getCleanOrphanTriggerCron());
    Assert.assertNull(parse(ImmutableMap.of()).getCleanOrphanTriggerCron());
  }

  @Test
  public void orphanExistingMinutesValidation() {
    Assert.assertEquals(
        2880L, parse(ImmutableMap.of()).getOrphanExistingMinutes());
    Assert.assertNull(parse(ImmutableMap.of()).getCleanOrphanConfigError());

    Assert.assertEquals(
        120L,
        parse(ImmutableMap.of("clean-orphan-file.min-existing-time-minutes", "120"))
            .getOrphanExistingMinutes());
    Assert.assertNull(
        parse(ImmutableMap.of("clean-orphan-file.min-existing-time-minutes", "120"))
            .getCleanOrphanConfigError());

    Assert.assertEquals(
        0L,
        parse(ImmutableMap.of("clean-orphan-file.min-existing-time-minutes", "0"))
            .getOrphanExistingMinutes());
    Assert.assertEquals(
        "invalid clean-orphan-file.min-existing-time-minutes '0'",
        parse(ImmutableMap.of("clean-orphan-file.min-existing-time-minutes", "0"))
            .getCleanOrphanConfigError());
  }

  @Test
  public void cronChangeIsAConfigChange() {
    TableConfiguration everyTwoMinutes =
        parse(
            ImmutableMap.of(
                "clean-orphan-files.enabled", "true", "clean-orphan-files.trigger.cron", "*/2 * * * *"));
    TableConfiguration daily =
        parse(
            ImmutableMap.of(
                "clean-orphan-files.enabled", "true", "clean-orphan-files.trigger.cron", "0 0 * * *"));
    Assert.assertNotEquals(everyTwoMinutes, daily);
  }
}
