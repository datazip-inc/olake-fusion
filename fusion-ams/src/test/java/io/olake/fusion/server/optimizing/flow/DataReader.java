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

package io.olake.fusion.server.optimizing.flow;

import io.olake.fusion.io.reader.GenericUnkeyedDataReader;
import io.olake.fusion.table.MixedTable;
import io.olake.fusion.table.UnkeyedTable;
import org.apache.amoro.shade.guava32.com.google.common.collect.Iterables;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.data.IdentityPartitionConverters;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class DataReader {
  private final MixedTable table;

  public DataReader(MixedTable table) {
    this.table = table;
  }

  public List<Record> allData() throws Exception {
    return readIceberg(table.asUnkeyedTable());
  }

  private List<Record> readIceberg(UnkeyedTable table)
      throws ExecutionException, InterruptedException {
    GenericUnkeyedDataReader dataReader =
        new GenericUnkeyedDataReader(
            table.io(),
            table.schema(),
            table.schema(),
            null,
            false,
            IdentityPartitionConverters::convertConstant,
            false);
    CloseableIterable<FileScanTask> fileScanTasks = table.newScan().planFiles();

    List<CompletableFuture<List<Record>>> completableFutures = new ArrayList<>();
    for (FileScanTask fileScanTask : fileScanTasks) {
      completableFutures.add(
          CompletableFuture.supplyAsync(
              () -> {
                CloseableIterable<Record> closeableIterable = dataReader.readData(fileScanTask);
                List<Record> list = new ArrayList<>();
                Iterables.addAll(list, closeableIterable);
                try {
                  closeableIterable.close();
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
                return list;
              }));
    }

    List<Record> list = new ArrayList<>();
    for (CompletableFuture<List<Record>> completableFuture : completableFutures) {
      List<Record> records = completableFuture.get();
      list.addAll(records);
    }
    return list;
  }
}
