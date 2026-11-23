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

/** Outcome of one orphan files cleaning run. */
public class OrphanFilesCleaningSummary {
  private final int orphanDataFilesExpected;
  private final int orphanDataFilesDeleted;
  private final int orphanMetadataFilesExpected;
  private final int orphanMetadataFilesDeleted;

  public OrphanFilesCleaningSummary(
      int orphanDataFilesExpected,
      int orphanDataFilesDeleted,
      int orphanMetadataFilesExpected,
      int orphanMetadataFilesDeleted) {
    this.orphanDataFilesExpected = orphanDataFilesExpected;
    this.orphanDataFilesDeleted = orphanDataFilesDeleted;
    this.orphanMetadataFilesExpected = orphanMetadataFilesExpected;
    this.orphanMetadataFilesDeleted = orphanMetadataFilesDeleted;
  }

  public int orphanDataFilesExpected() {
    return orphanDataFilesExpected;
  }

  public int orphanDataFilesDeleted() {
    return orphanDataFilesDeleted;
  }

  public int orphanMetadataFilesExpected() {
    return orphanMetadataFilesExpected;
  }

  public int orphanMetadataFilesDeleted() {
    return orphanMetadataFilesDeleted;
  }

  public int totalExpected() {
    return orphanDataFilesExpected + orphanMetadataFilesExpected;
  }

  public int totalDeleted() {
    return orphanDataFilesDeleted + orphanMetadataFilesDeleted;
  }
}
