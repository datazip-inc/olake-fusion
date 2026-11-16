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

package org.apache.amoro.formats.iceberg.maintainer;

import org.apache.iceberg.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Outcome of one snapshot expiry commit. */
public class SnapshotExpirySummary {
  private final int snapshotsBefore;
  private final int snapshotsRetained;
  private final List<Snapshot> expiredSnapshots;

  public SnapshotExpirySummary(
      int snapshotsBefore, int snapshotsRetained, List<Snapshot> expiredSnapshots) {
    this.snapshotsBefore = snapshotsBefore;
    this.snapshotsRetained = snapshotsRetained;
    this.expiredSnapshots = Collections.unmodifiableList(new ArrayList<>(expiredSnapshots));
  }

  public int snapshotsBefore() {
    return snapshotsBefore;
  }

  public int snapshotsRetained() {
    return snapshotsRetained;
  }

  public int snapshotsExpired() {
    return expiredSnapshots.size();
  }

  /** The expired snapshots, oldest first. */
  public List<Snapshot> expiredSnapshots() {
    return expiredSnapshots;
  }
}
