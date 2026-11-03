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

package io.olake.fusion.optimizing;

import org.apache.amoro.shade.guava32.com.google.common.base.MoreObjects;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.SerializableTable;
import org.apache.iceberg.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The input of one rewrite task. The server builds it when it plans a process, stores it in
 * Postgres ({@code optimizing_process_state.rewrite_input}) and sends it to the optimizer, both as
 * Java-serialized bytes.
 *
 * <p>Everything in it is either an Iceberg type or a JDK type, so the stored form names no Fusion
 * class except this one and {@link BaseOptimizingInput}:
 *
 * <ul>
 *   <li>the files to rewrite, as Iceberg {@link DataFile}s and delete files;
 *   <li>the table, as a read-only Iceberg {@link SerializableTable} (schema, specs, sort order,
 *       location provider, encryption and the table's {@code FileIO} with its credentials). The
 *       optimizer reads and writes files with it and commits nothing; the server commits;
 *   <li>the table properties with the catalog's {@code table.*} defaults merged in, which decide
 *       the output file format and size.
 * </ul>
 *
 * <p>This is format 2. Format 1, the Amoro form that carried Fusion's mixed-format table wrapper,
 * cannot be read: its {@code serialVersionUID} differs, so reading it fails with an {@link
 * java.io.InvalidClassException} instead of producing a half-read input.
 */
public class RewriteFilesInput extends BaseOptimizingInput {

  /**
   * Format version of the serialized task input. Change it whenever the serialized form changes;
   * optimizers report it on registration (see {@code OptimizerProperties#OPTIMIZER_TASK_FORMAT}).
   */
  public static final long FORMAT_VERSION = 2L;

  private static final long serialVersionUID = FORMAT_VERSION;

  private final DataFile[] rewrittenDataFiles;
  private final DataFile[] rePosDeletedDataFiles;
  private final ContentFile<?>[] readOnlyDeleteFiles;
  private final ContentFile<?>[] rewrittenDeleteFiles;
  private final Table table;
  private final HashMap<String, String> tableProperties;

  /**
   * @param table the table to rewrite; anything other than a {@link SerializableTable} is copied
   *     into one
   * @param tableProperties table properties with the catalog defaults merged in; never changed
   *     after it is passed in
   */
  public RewriteFilesInput(
      DataFile[] rewrittenDataFiles,
      DataFile[] rePosDeletedDataFiles,
      ContentFile<?>[] readOnlyDeleteFiles,
      ContentFile<?>[] rewrittenDeleteFiles,
      Table table,
      Map<String, String> tableProperties) {
    Preconditions.checkNotNull(table, "table");
    this.rewrittenDataFiles = rewrittenDataFiles;
    this.rePosDeletedDataFiles = rePosDeletedDataFiles;
    this.readOnlyDeleteFiles = readOnlyDeleteFiles;
    this.rewrittenDeleteFiles = rewrittenDeleteFiles;
    this.table = table instanceof SerializableTable ? table : SerializableTable.copyOf(table);
    Map<String, String> properties = tableProperties == null ? table.properties() : tableProperties;
    // Inputs of one process share the map, so it is serialized once with them.
    this.tableProperties =
        properties instanceof HashMap
            ? (HashMap<String, String>) properties
            : new HashMap<>(properties);
  }

  public DataFile[] rewrittenDataFiles() {
    return rewrittenDataFiles;
  }

  public DataFile[] rePosDeletedDataFiles() {
    return rePosDeletedDataFiles;
  }

  public ContentFile<?>[] readOnlyDeleteFiles() {
    return readOnlyDeleteFiles;
  }

  public ContentFile<?>[] rewrittenDeleteFiles() {
    return rewrittenDeleteFiles;
  }

  public ContentFile<?>[] deleteFiles() {
    List<ContentFile<?>> list = new ArrayList<>();
    if (readOnlyDeleteFiles != null) {
      list.addAll(Arrays.asList(readOnlyDeleteFiles));
    }
    if (rewrittenDeleteFiles != null) {
      list.addAll(Arrays.asList(rewrittenDeleteFiles));
    }
    return list.toArray(new ContentFile<?>[0]);
  }

  public DataFile[] dataFiles() {
    List<DataFile> list = new ArrayList<>();
    if (rewrittenDataFiles != null) {
      list.addAll(Arrays.asList(rewrittenDataFiles));
    }
    if (rePosDeletedDataFiles != null) {
      list.addAll(Arrays.asList(rePosDeletedDataFiles));
    }
    return list.toArray(new DataFile[0]);
  }

  public ContentFile<?>[] allFiles() {
    List<ContentFile<?>> list = new ArrayList<>();
    if (rewrittenDataFiles != null) {
      list.addAll(Arrays.asList(rewrittenDataFiles));
    }
    if (rePosDeletedDataFiles != null) {
      list.addAll(Arrays.asList(rePosDeletedDataFiles));
    }
    if (readOnlyDeleteFiles != null) {
      list.addAll(Arrays.asList(readOnlyDeleteFiles));
    }
    if (rewrittenDeleteFiles != null) {
      list.addAll(Arrays.asList(rewrittenDeleteFiles));
    }
    return list.toArray(new ContentFile<?>[0]);
  }

  /** Returns the table, a read-only {@link SerializableTable}. */
  public Table getTable() {
    return table;
  }

  /** Returns the table properties with the catalog defaults merged in. */
  public Map<String, String> getTableProperties() {
    return Collections.unmodifiableMap(tableProperties);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("rewrittenDataFilesSize", rewrittenDataFiles.length)
        .add("rePosDeletedDataFilesSize", rePosDeletedDataFiles.length)
        .add("readOnlyDeleteFilesSize", readOnlyDeleteFiles.length)
        .add("rewrittenDeleteFilesSize", rewrittenDeleteFiles.length)
        .add("table", table.name())
        .addValue(super.toString())
        .toString();
  }
}
