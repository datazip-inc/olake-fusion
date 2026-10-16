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

package io.olake.fusion.it.harness;

import java.time.Duration;

/**
 * The user's bucket, reached with the MinIO client as the bucket owner. The plan-failure journey
 * moves a manifest out of place and back with it.
 */
public final class Bucket {
  private static final String MC_IMAGE = "olakego/mc:RELEASE.2025-04-03T17-07-56Z";
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private Bucket() {}

  /** Moves the object at {@code from} to {@code to} (both s3://bucket/key). */
  public static void move(String from, String to) {
    mc("mv " + local(from) + " " + local(to));
  }

  /** Runs {@code mc <command>} against the stack's MinIO as its owner. */
  private static String mc(String command) {
    return Docker.run(
        TIMEOUT,
        "run",
        "--rm",
        "--network",
        Env.DOCKER_NETWORK,
        "--entrypoint",
        "sh",
        MC_IMAGE,
        "-c",
        "mc alias set minio http://minio:9000 admin password >/dev/null && mc " + command);
  }

  private static String local(String s3Path) {
    if (!s3Path.startsWith("s3://")) {
      throw new IllegalArgumentException("not an s3:// path: " + s3Path);
    }
    return "minio/" + s3Path.substring("s3://".length());
  }
}
