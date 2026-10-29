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

import io.olake.fusion.data.PrimaryKeyedFile;
import io.olake.fusion.io.reader.AbstractKeyedDataReader;
import io.olake.fusion.io.reader.GenericKeyedDataReader;
import io.olake.fusion.scan.BasicMixedFileScanTask;
import io.olake.fusion.scan.MixedFileScanTask;
import io.olake.fusion.scan.NodeFileScanTask;
import io.olake.fusion.table.KeyedTable;
import io.olake.fusion.table.MixedTable;
import io.olake.fusion.table.PrimaryKeySpec;
import io.olake.fusion.utils.map.StructLikeCollections;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.IdentityPartitionConverters;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.CloseableIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MixedIcebergOptimizingDataReader implements OptimizingDataReader {
  private final MixedTable table;

  private final StructLikeCollections structLikeCollections;

  private final RewriteFilesInput input;

  public MixedIcebergOptimizingDataReader(
      MixedTable table, StructLikeCollections structLikeCollections, RewriteFilesInput input) {
    this.table = table;
    this.structLikeCollections = structLikeCollections;
    this.input = input;
  }

  @Override
  public CloseableIterable<Record> readData() {
    AbstractKeyedDataReader<Record> reader = mixedTableDataReader(table.schema());

    // Change returned value by readData  from Iterator to Iterable in future
    CloseableIterator<Record> closeableIterator =
        reader.readData(nodeFileScanTask(input.rewrittenDataFilesForMixed()));
    return wrapIterator2Iterable(closeableIterator);
  }

  @Override
  public CloseableIterable<Record> readDeletedData() {
    Schema schema =
        new Schema(
            MetadataColumns.FILE_PATH,
            MetadataColumns.ROW_POSITION,
            io.olake.fusion.table.MetadataColumns.TREE_NODE_FIELD);
    AbstractKeyedDataReader<Record> reader = mixedTableDataReader(schema);
    return wrapIterator2Iterable(
        reader.readDeletedData(nodeFileScanTask(input.rePosDeletedDataFilesForMixed())));
  }

  @Override
  public void close() {}

  protected AbstractKeyedDataReader<Record> mixedTableDataReader(Schema requiredSchema) {

    PrimaryKeySpec primaryKeySpec = PrimaryKeySpec.noPrimaryKey();
    if (table.isKeyedTable()) {
      KeyedTable keyedTable = table.asKeyedTable();
      primaryKeySpec = keyedTable.primaryKeySpec();
    }

    return new GenericKeyedDataReader(
        table.io(),
        table.schema(),
        requiredSchema,
        primaryKeySpec,
        table.properties().get(TableProperties.DEFAULT_NAME_MAPPING),
        false,
        IdentityPartitionConverters::convertConstant,
        null,
        false,
        structLikeCollections);
  }

  private NodeFileScanTask nodeFileScanTask(List<PrimaryKeyedFile> dataFiles) {
    List<DeleteFile> posDeleteList = input.positionDeleteForMixed();

    List<PrimaryKeyedFile> equlityDeleteList = input.equalityDeleteForMixed();

    List<PrimaryKeyedFile> allTaskFiles = new ArrayList<>();
    allTaskFiles.addAll(equlityDeleteList);
    allTaskFiles.addAll(dataFiles);

    List<MixedFileScanTask> fileScanTasks =
        allTaskFiles.stream()
            .map(file -> new BasicMixedFileScanTask(file, posDeleteList, table.spec()))
            .collect(Collectors.toList());
    return new NodeFileScanTask(fileScanTasks);
  }

  private CloseableIterable<Record> wrapIterator2Iterable(CloseableIterator<Record> iterator) {
    return new CloseableIterable<Record>() {
      @Override
      public CloseableIterator<Record> iterator() {
        return iterator;
      }

      @Override
      public void close() throws IOException {
        iterator.close();
      }
    };
  }
}
