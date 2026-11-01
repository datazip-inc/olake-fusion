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

package io.olake.fusion.io;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.table.TableProperties;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Files;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.MetricsModes;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TestHelpers;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.avro.DataReader;
import org.apache.iceberg.data.orc.GenericOrcReader;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.TaskWriter;
import org.apache.iceberg.io.UnpartitionedWriter;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.orc.ORC;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.transforms.Transform;
import org.apache.iceberg.transforms.Transforms;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PropertyUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Writes and reads test data of Iceberg tables with the Iceberg generic writers and readers. */
public class DataTestHelpers {

  public static Record createRecord(int id, String name, long ts, String opTime) {
    return createRecord(BasicTableTestHelper.TABLE_SCHEMA, id, name, ts, opTime);
  }

  public static Record createRecord(Schema recordSchema, Object... values) {
    Preconditions.checkArgument(
        recordSchema.columns().size() == values.length,
        "The number of values in the record does not match the number of fields in the schema");
    GenericRecord record = GenericRecord.create(recordSchema);
    for (int i = 0; i < recordSchema.columns().size(); i++) {
      record.set(i, adaptRecordValueByType(recordSchema.columns().get(i).type(), values[i]));
    }
    return record;
  }

  private static Object adaptRecordValueByType(Type type, Object value) {
    if (Types.TimestampType.withoutZone().equals(type) && value instanceof String) {
      return LocalDateTime.parse((String) value);
    } else if (Types.TimestampType.withZone().equals(type) && value instanceof String) {
      return OffsetDateTime.parse((String) value);
    }
    return value;
  }

  public static StructLike recordPartition(String opTime) {
    Transform<Long, Integer> day = Transforms.day(Types.TimestampType.withoutZone());

    Literal<Long> ts = Literal.of(opTime).to(Types.TimestampType.withoutZone());
    Object tsDay = day.apply(ts.value());

    return TestHelpers.Row.of(tsDay);
  }

  public static List<DataFile> writeRecords(TaskWriter<Record> taskWriter, List<Record> records) {
    try {
      records.forEach(
          d -> {
            try {
              taskWriter.write(d);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });

      WriteResult result = taskWriter.complete();
      return Arrays.asList(result.dataFiles());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes the records as data files of the table, without committing them. */
  public static List<DataFile> writeDataFiles(Table table, List<Record> records) {
    try (TaskWriter<Record> writer = dataWriter(table)) {
      return writeRecords(writer, records);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes and commits the records as data files of the table. */
  public static List<DataFile> writeAndCommitDataFiles(Table table, List<Record> records) {
    List<DataFile> dataFiles = writeDataFiles(table, records);
    AppendFiles appendFiles = table.newAppend();
    dataFiles.forEach(appendFiles::appendFile);
    appendFiles.commit();
    return dataFiles;
  }

  /** Writes a position delete file deleting rows of a data file, without committing it. */
  public static List<DeleteFile> writePosDeletes(Table table, DataFile dataFile, List<Long> pos) {
    GenericAppenderFactory appenderFactory =
        new GenericAppenderFactory(table.schema(), table.spec());
    appenderFactory.setAll(table.properties());
    // full metrics on the path, so the delete file is linked to the one data file it deletes from
    appenderFactory.set(
        org.apache.iceberg.TableProperties.METRICS_MODE_COLUMN_CONF_PREFIX
            + MetadataColumns.DELETE_FILE_PATH.name(),
        MetricsModes.Full.get().toString());
    appenderFactory.set(
        org.apache.iceberg.TableProperties.METRICS_MODE_COLUMN_CONF_PREFIX
            + MetadataColumns.DELETE_FILE_POS.name(),
        MetricsModes.Full.get().toString());
    OutputFileFactory outputFileFactory = OutputFileFactory.builderFor(table, 0, 0).build();
    PositionDeleteWriter<Record> writer =
        appenderFactory.newPosDeleteWriter(
            outputFileFactory.newOutputFile(
                table.specs().get(dataFile.specId()), dataFile.partition()),
            fileFormat(table),
            dataFile.partition());
    try (PositionDeleteWriter<Record> closing = writer) {
      PositionDelete<Record> delete = PositionDelete.create();
      for (Long p : pos.stream().sorted().collect(Collectors.toList())) {
        closing.write(delete.set(dataFile.path(), p, null));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Lists.newArrayList(writer.toDeleteFile());
  }

  /** Refreshes the table and reads its rows, deletes applied. */
  public static List<Record> readTable(Table table, Expression expression) {
    table.refresh();
    return readTable(table, expression, null);
  }

  /** Reads the rows of the table's current snapshot, deletes applied. */
  public static List<Record> readTable(Table table, Expression expression, Schema projectSchema) {
    IcebergGenerics.ScanBuilder scan = IcebergGenerics.read(table).where(expression);
    if (projectSchema != null) {
      scan = scan.project(projectSchema);
    }
    try (CloseableIterable<Record> records = scan.build()) {
      return Lists.newArrayList(records);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static TaskWriter<Record> dataWriter(Table table) {
    FileFormat format = fileFormat(table);
    GenericAppenderFactory appenderFactory =
        new GenericAppenderFactory(table.schema(), table.spec());
    appenderFactory.setAll(table.properties());
    OutputFileFactory outputFileFactory =
        OutputFileFactory.builderFor(table, 0, 0).format(format).build();
    long targetSize =
        PropertyUtil.propertyAsLong(
            table.properties(),
            TableProperties.WRITE_TARGET_FILE_SIZE_BYTES,
            TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT);
    if (table.spec().isUnpartitioned()) {
      return new UnpartitionedWriter<>(
          table.spec(), format, appenderFactory, outputFileFactory, table.io(), targetSize);
    }
    return new io.olake.fusion.io.writer.GenericIcebergPartitionedFanoutWriter(
        table.schema(),
        table.spec(),
        format,
        appenderFactory,
        outputFileFactory,
        table.io(),
        targetSize);
  }

  private static FileFormat fileFormat(Table table) {
    String format =
        table
            .properties()
            .getOrDefault(
                TableProperties.BASE_FILE_FORMAT,
                table
                    .properties()
                    .getOrDefault(
                        TableProperties.DEFAULT_FILE_FORMAT,
                        TableProperties.DEFAULT_FILE_FORMAT_DEFAULT));
    return FileFormat.valueOf(format.toUpperCase(Locale.ENGLISH));
  }

  public static List<Record> readDataFile(FileFormat format, Schema schema, CharSequence path)
      throws IOException {
    CloseableIterable<Record> iterable;

    InputFile inputFile = Files.localInput(path.toString());
    switch (format) {
      case PARQUET:
        iterable =
            Parquet.read(inputFile)
                .project(schema)
                .createReaderFunc(
                    fileSchema -> GenericParquetReaders.buildReader(schema, fileSchema))
                .build();
        break;

      case AVRO:
        iterable =
            Avro.read(inputFile).project(schema).createReaderFunc(DataReader::create).build();
        break;

      case ORC:
        iterable =
            ORC.read(inputFile)
                .project(schema)
                .createReaderFunc(fileSchema -> GenericOrcReader.buildReader(schema, fileSchema))
                .build();
        break;

      default:
        throw new UnsupportedOperationException("Unsupported file format: " + format);
    }

    try (CloseableIterable<Record> closeableIterable = iterable) {
      return Lists.newArrayList(closeableIterable);
    }
  }

  public static DataFile wrapIcebergDataFile(DataFile dataFile, Long dataSequenceNumber) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getName().equals("dataSequenceNumber") && (args == null || args.length == 0)) {
            return dataSequenceNumber;
          } else {
            return method.invoke(dataFile, args);
          }
        };
    return (DataFile)
        Proxy.newProxyInstance(
            DataFile.class.getClassLoader(), new Class[] {DataFile.class}, handler);
  }

  public static DeleteFile wrapIcebergDeleteFile(DeleteFile deleteFile, Long dataSequenceNumber) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getName().equals("dataSequenceNumber") && (args == null || args.length == 0)) {
            return dataSequenceNumber;
          } else {
            return method.invoke(deleteFile, args);
          }
        };
    return (DeleteFile)
        Proxy.newProxyInstance(
            DeleteFile.class.getClassLoader(), new Class[] {DeleteFile.class}, handler);
  }
}
