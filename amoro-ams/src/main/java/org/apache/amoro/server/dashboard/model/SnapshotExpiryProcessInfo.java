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

package org.apache.amoro.server.dashboard.model;

import org.apache.amoro.process.ProcessStatus;
import org.apache.amoro.server.persistence.SnapshotExpiryProcessMeta;

import java.util.Map;

/** One snapshot expiry run as the REST API returns it. */
public class SnapshotExpiryProcessInfo {
  private final String processId;
  private final ProcessStatus status;
  private final long startTime;
  private final long finishTime;
  private final long duration;
  private final String failReason;
  private final Map<String, String> summary;

  private SnapshotExpiryProcessInfo(SnapshotExpiryProcessMeta meta, long now) {
    this.processId = String.valueOf(meta.getProcessId());
    this.status = meta.getStatus();
    this.startTime = meta.getCreateTime();
    this.finishTime = meta.getFinishTime();
    this.duration = (meta.getFinishTime() > 0 ? meta.getFinishTime() : now) - meta.getCreateTime();
    this.failReason = meta.getFailMessage();
    this.summary = meta.getSummary();
  }

  public static SnapshotExpiryProcessInfo of(SnapshotExpiryProcessMeta meta, long now) {
    return new SnapshotExpiryProcessInfo(meta, now);
  }

  public String getProcessId() {
    return processId;
  }

  public ProcessStatus getStatus() {
    return status;
  }

  public long getStartTime() {
    return startTime;
  }

  public long getFinishTime() {
    return finishTime;
  }

  public long getDuration() {
    return duration;
  }

  public String getFailReason() {
    return failReason;
  }

  public Map<String, String> getSummary() {
    return summary;
  }
}
