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

import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Live content files of one Iceberg snapshot, read from its manifests. */
public class TableLayout {

  private final long snapshotId;
  private final List<DataFile> dataFiles;
  private final List<DeleteFile> deleteFiles;
  private final Map<String, String> partitionOf;

  TableLayout(
      long snapshotId,
      List<DataFile> dataFiles,
      List<DeleteFile> deleteFiles,
      Map<String, String> partitionOf) {
    this.snapshotId = snapshotId;
    this.dataFiles = Collections.unmodifiableList(dataFiles);
    this.deleteFiles = Collections.unmodifiableList(deleteFiles);
    this.partitionOf = partitionOf;
  }

  public long snapshotId() {
    return snapshotId;
  }

  public List<DataFile> dataFiles() {
    return dataFiles;
  }

  public Set<String> dataFilePaths() {
    return dataFiles.stream().map(f -> f.location()).collect(Collectors.toSet());
  }

  public List<DeleteFile> deleteFiles() {
    return deleteFiles;
  }

  public List<DeleteFile> equalityDeletes() {
    return deletesOf(FileContent.EQUALITY_DELETES);
  }

  public List<DeleteFile> positionDeletes() {
    return deletesOf(FileContent.POSITION_DELETES);
  }

  public long deleteRecordCount() {
    return deleteFiles.stream().mapToLong(DeleteFile::recordCount).sum();
  }

  /** Partition path ("" when unpartitioned) to the number of data files / delete files in it. */
  public Map<String, int[]> filesPerPartition() {
    Map<String, int[]> counts = new TreeMap<>();
    for (DataFile file : dataFiles) {
      counts.computeIfAbsent(partitionOf.get(file.location()), k -> new int[2])[0]++;
    }
    for (DeleteFile file : deleteFiles) {
      counts.computeIfAbsent(partitionOf.get(file.location()), k -> new int[2])[1]++;
    }
    return counts;
  }

  private List<DeleteFile> deletesOf(FileContent content) {
    List<DeleteFile> files = new ArrayList<>();
    for (DeleteFile file : deleteFiles) {
      if (file.content() == content) {
        files.add(file);
      }
    }
    return files;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("snapshot=")
        .append(snapshotId)
        .append(", dataFiles=")
        .append(dataFiles.size())
        .append(", eqDeletes=")
        .append(equalityDeletes().size())
        .append(", posDeletes=")
        .append(positionDeletes().size())
        .append(", perPartition{");
    filesPerPartition()
        .forEach(
            (partition, c) ->
                sb.append('[')
                    .append(partition)
                    .append("] data=")
                    .append(c[0])
                    .append(" delete=")
                    .append(c[1])
                    .append("; "));
    return sb.append('}').toString();
  }
}
