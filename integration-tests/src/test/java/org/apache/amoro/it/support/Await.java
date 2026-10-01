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

package org.apache.amoro.it.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Poll-until helper. A timeout fails the test with the last observed value. */
public final class Await {

  private static final Logger LOG = LoggerFactory.getLogger(Await.class);
  private static final Duration DEFAULT_POLL = Duration.ofSeconds(5);

  private Await() {}

  public static <T> T until(
      String description, Duration timeout, Supplier<T> probe, Predicate<T> done) {
    return until(description, timeout, DEFAULT_POLL, probe, done);
  }

  public static <T> T until(
      String description, Duration timeout, Duration poll, Supplier<T> probe, Predicate<T> done) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    T last = null;
    RuntimeException lastError = null;
    while (true) {
      try {
        last = probe.get();
        lastError = null;
        if (done.test(last)) {
          return last;
        }
      } catch (RuntimeException e) {
        lastError = e;
      }
      if (System.currentTimeMillis() >= deadline) {
        AssertionError error =
            new AssertionError(
                String.format(
                    "Timed out after %s waiting for: %s. Last value: %s",
                    timeout, description, last));
        if (lastError != null) {
          error.initCause(lastError);
        }
        throw error;
      }
      LOG.debug("Waiting for {}; last value {}", description, last);
      sleep(poll);
    }
  }

  public static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting", e);
    }
  }
}
