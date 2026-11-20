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

package io.olake.fusion.dev;

import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.shade.guava32.com.google.common.collect.Sets;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.types.Types;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Debug tool behind {@code make debug-data}: creates an Iceberg table in the debug catalog with
 * small data files and position or equality delete files, one commit each. {@code --update} re-adds
 * each deleted row with {@code version} + 1, like an upsert.
 */
public class IcebergTestDataGenerator {

  private static final Schema SCHEMA =
      new Schema(
          Lists.newArrayList(
              Types.NestedField.required(1, "id", Types.LongType.get()),
              Types.NestedField.optional(2, "name", Types.StringType.get()),
              Types.NestedField.optional(3, "value", Types.DoubleType.get()),
              Types.NestedField.optional(4, "part", Types.IntegerType.get()),
              Types.NestedField.optional(5, "version", Types.IntegerType.get())),
          Sets.newHashSet(1));

  private static final String USAGE =
      String.join(
          "\n",
          "Usage: make debug-data ARGS=\"[options]\"",
          "",
          "  --table <db.table>        table to create (default demo.events)",
          "  --files <n>               data files to write, one commit each (default 20)",
          "  --rows <n>                rows per data file (default 100)",
          "  --pos <n>                 position delete files to write (default 0)",
          "  --eq <n>                  equality delete files (on column id) to write (default 0)",
          "  --update                  delete commits also add the new row versions (an update)",
          "  --deletes-per-file <n>    rows each delete file removes (default 10)",
          "  --partitions <n>          identity-partition on column part with n values (default 0:"
              + " unpartitioned)",
          "  --recreate                drop the table first if it exists",
          "  --minor-cron <cron>       enable optimizing and set self-optimizing.minor.trigger.cron",
          "  --major-cron <cron>       enable optimizing and set self-optimizing.major.trigger.cron",
          "  --full-cron <cron>        enable optimizing and set self-optimizing.full.trigger.cron",
          "  --seed <n>                random seed for the column values (default 42)",
          "",
          "Connection (set by make from DEBUG_DB and DEBUG_CATALOG):",
          "  --catalog <name>          Iceberg catalog name, the Fusion catalog name (default"
              + " local_iceberg)",
          "  --uri <jdbc url>          JDBC catalog database (default"
              + " jdbc:postgresql://localhost:5432/fusion_dev)",
          "  --warehouse <s3 path>     default s3://warehouse/<catalog>/",
          "  --s3-endpoint <url>       default http://localhost:9000");

  private final Map<String, String> options;
  private final Random random;
  private final FileFormat format = FileFormat.PARQUET;

  private Table table;
  private OutputFileFactory files;
  private GenericAppenderFactory appenders;
  private long nextId = 1;
  private long liveRows = 0;

  private final List<WrittenFile> written = new ArrayList<>();

  /** Each id is deleted or updated at most once, so the printed row count stays exact. */
  private final Set<Long> touchedIds = Sets.newHashSet();

  private IcebergTestDataGenerator(Map<String, String> options) {
    this.options = options;
    this.random = new Random(intOption("seed", 42));
  }

  public static void main(String[] args) throws IOException {
    Map<String, String> options = parse(args);
    if (options.containsKey("help")) {
      System.out.println(USAGE);
      return;
    }
    new IcebergTestDataGenerator(options).run();
  }

  private void run() throws IOException {
    for (String name : NUMBER_OPTIONS) {
      intOption(name, 0);
    }
    Catalog catalog = buildCatalog();
    TableIdentifier identifier = TableIdentifier.parse(option("table", "demo.events"));
    createTable(catalog, identifier);

    int fileCount = intOption("files", 20);
    int rowsPerFile = intOption("rows", 100);
    int partitions = intOption("partitions", 0);
    for (int i = 0; i < fileCount; i++) {
      appendDataFile(rowsPerFile, partitions == 0 ? 0 : i % partitions);
    }

    boolean update = options.containsKey("update");
    int deletesPerFile = intOption("deletes-per-file", 10);
    int posFiles = 0;
    for (int i = 0; i < intOption("pos", 0) && !written.isEmpty(); i++) {
      posFiles += commitPositionDeletes(written.get(i % written.size()), deletesPerFile, update);
    }
    int eqFiles = 0;
    for (int i = 0; i < intOption("eq", 0) && !written.isEmpty(); i++) {
      eqFiles += commitEqualityDeletes(written.get(i % written.size()), deletesPerFile, update);
    }

    table.refresh();
    System.out.printf(
        Locale.ROOT,
        "%nTable %s.%s at %s%n"
            + "  data files written: %d (+ %d with updated rows)%n"
            + "  position delete files: %d, equality delete files: %d%n"
            + "  rows expected in a query: %d%n"
            + "  snapshots: %d%n"
            + "Check in the Fusion terminal: SELECT count(*) FROM %s%n",
        option("catalog", "local_iceberg"),
        identifier,
        table.location(),
        fileCount,
        update ? posFiles + eqFiles : 0,
        posFiles,
        eqFiles,
        liveRows,
        Lists.newArrayList(table.snapshots()).size(),
        identifier);
  }

  private Catalog buildCatalog() {
    String catalogName = option("catalog", "local_iceberg");
    Map<String, String> properties = Maps.newHashMap();
    properties.put(CatalogProperties.CATALOG_IMPL, JdbcCatalog.class.getName());
    properties.put(
        CatalogProperties.URI, option("uri", "jdbc:postgresql://localhost:5432/fusion_dev"));
    properties.put(JdbcCatalog.PROPERTY_PREFIX + "user", option("jdbc-user", "iceberg"));
    properties.put(JdbcCatalog.PROPERTY_PREFIX + "password", option("jdbc-password", "password"));
    properties.put(JdbcCatalog.PROPERTY_PREFIX + "schema-version", "V1");
    properties.put(
        CatalogProperties.WAREHOUSE_LOCATION,
        option("warehouse", "s3://warehouse/" + catalogName + "/"));
    properties.put(CatalogProperties.FILE_IO_IMPL, S3FileIO.class.getName());
    properties.put(S3FileIOProperties.ENDPOINT, option("s3-endpoint", "http://localhost:9000"));
    properties.put(S3FileIOProperties.PATH_STYLE_ACCESS, "true");
    properties.put(S3FileIOProperties.ACCESS_KEY_ID, option("s3-access-key", "admin"));
    properties.put(S3FileIOProperties.SECRET_ACCESS_KEY, option("s3-secret-key", "password"));
    properties.put(AwsClientProperties.CLIENT_REGION, option("s3-region", "us-east-1"));
    return CatalogUtil.buildIcebergCatalog(catalogName, properties, new Configuration());
  }

  private void createTable(Catalog catalog, TableIdentifier identifier) {
    Namespace namespace = identifier.namespace();
    SupportsNamespaces namespaces = (SupportsNamespaces) catalog;
    if (!namespaces.namespaceExists(namespace)) {
      namespaces.createNamespace(namespace);
    }
    if (catalog.tableExists(identifier)) {
      if (!options.containsKey("recreate")) {
        throw new IllegalStateException(
            "Table " + identifier + " already exists; pass --recreate to drop it first");
      }
      catalog.dropTable(identifier, true);
      System.out.println("Dropped " + identifier);
    }

    int partitions = intOption("partitions", 0);
    PartitionSpec spec =
        partitions > 0
            ? PartitionSpec.builderFor(SCHEMA).identity("part").build()
            : PartitionSpec.unpartitioned();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.FORMAT_VERSION, "2");
    properties.put(TableProperties.DEFAULT_FILE_FORMAT, format.name().toLowerCase(Locale.ROOT));
    for (String type : new String[] {"minor", "major", "full"}) {
      String cron = options.get(type + "-cron");
      if (cron != null) {
        properties.put("self-optimizing.enabled", "true");
        properties.put("self-optimizing." + type + ".trigger.cron", cron);
      }
    }
    table = catalog.createTable(identifier, SCHEMA, spec, properties);
    System.out.println("Created " + identifier + " (" + spec + ")");

    files = OutputFileFactory.builderFor(table, 1, 1).format(format).build();
    Schema equalityRowSchema = SCHEMA.select("id");
    appenders =
        new GenericAppenderFactory(
            SCHEMA, spec, new int[] {SCHEMA.findField("id").fieldId()}, equalityRowSchema, null);
  }

  private void appendDataFile(int rows, int part) throws IOException {
    List<Long> ids = new ArrayList<>(rows);
    List<Record> records = new ArrayList<>(rows);
    for (int i = 0; i < rows; i++) {
      long id = nextId++;
      ids.add(id);
      records.add(row(id, part, 1));
    }
    DataFile dataFile = writeData(records);
    table.newAppend().appendFile(dataFile).commit();
    written.add(new WrittenFile(dataFile, ids));
    liveRows += rows;
  }

  /** Returns 1 if a delete file was written, 0 if the target had no rows left. */
  private int commitPositionDeletes(WrittenFile target, int count, boolean update)
      throws IOException {
    List<Integer> positions = target.takeUntouched(count, touchedIds);
    if (positions.isEmpty()) {
      System.out.println("Skipped a position delete: no rows left in " + target.file.path());
      return 0;
    }
    StructLike partition = partitionOf(target.file);
    PositionDeleteWriter<Record> writer =
        appenders.newPosDeleteWriter(newOutputFile(partition), format, partition);
    PositionDelete<Record> delete = PositionDelete.create();
    List<Record> updated = new ArrayList<>();
    try (PositionDeleteWriter<Record> w = writer) {
      for (int position : positions) {
        w.write(delete.set(target.file.path(), position, null));
        updated.add(row(target.ids.get(position), partValue(target.file), 2));
      }
    }
    commitDeletes(writer.toDeleteFile(), update ? updated : null);
    return 1;
  }

  /** Returns 1 if a delete file was written, 0 if the target had no rows left. */
  private int commitEqualityDeletes(WrittenFile target, int count, boolean update)
      throws IOException {
    List<Integer> positions = target.takeUntouched(count, touchedIds);
    if (positions.isEmpty()) {
      System.out.println("Skipped an equality delete: no rows left in " + target.file.path());
      return 0;
    }
    StructLike partition = partitionOf(target.file);
    EqualityDeleteWriter<Record> writer =
        appenders.newEqDeleteWriter(newOutputFile(partition), format, partition);
    Record key = GenericRecord.create(SCHEMA.select("id"));
    List<Record> updated = new ArrayList<>();
    try (EqualityDeleteWriter<Record> w = writer) {
      for (int position : positions) {
        long id = target.ids.get(position);
        w.write(key.copy("id", id));
        updated.add(row(id, partValue(target.file), 2));
      }
    }
    commitDeletes(writer.toDeleteFile(), update ? updated : null);
    return 1;
  }

  /**
   * Updated rows go in the same commit: they share the delete's sequence number, so an equality
   * delete does not remove them.
   */
  private void commitDeletes(DeleteFile deleteFile, List<Record> updatedRows) throws IOException {
    RowDelta rowDelta = table.newRowDelta().addDeletes(deleteFile);
    liveRows -= deleteFile.recordCount();
    if (updatedRows != null) {
      rowDelta.addRows(writeData(updatedRows));
      liveRows += updatedRows.size();
    }
    rowDelta.commit();
  }

  private DataFile writeData(List<Record> records) throws IOException {
    StructLike partition = null;
    if (table.spec().isPartitioned()) {
      PartitionKey key = new PartitionKey(table.spec(), table.schema());
      key.partition(records.get(0));
      partition = key;
    }
    DataWriter<Record> writer =
        appenders.newDataWriter(newOutputFile(partition), format, partition);
    try (DataWriter<Record> w = writer) {
      for (Record record : records) {
        w.write(record);
      }
    }
    return writer.toDataFile();
  }

  private EncryptedOutputFile newOutputFile(StructLike partition) {
    return partition == null ? files.newOutputFile() : files.newOutputFile(table.spec(), partition);
  }

  private StructLike partitionOf(DataFile file) {
    return table.spec().isPartitioned() ? file.partition() : null;
  }

  private int partValue(DataFile file) {
    return table.spec().isPartitioned() ? file.partition().get(0, Integer.class) : 0;
  }

  private Record row(long id, int part, int version) {
    Record record = GenericRecord.create(SCHEMA);
    record.setField("id", id);
    record.setField("name", "name-" + id);
    record.setField("value", Math.round(random.nextDouble() * 100_000) / 100.0);
    record.setField("part", part);
    record.setField("version", version);
    return record;
  }

  private String option(String name, String defaultValue) {
    return options.getOrDefault(name, defaultValue);
  }

  private int intOption(String name, int defaultValue) {
    String value = options.get(name);
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + name + " needs a non-negative number: " + value);
    }
  }

  private static final Set<String> FLAGS = Sets.newHashSet("update", "recreate", "help");

  private static final Set<String> NUMBER_OPTIONS =
      Sets.newHashSet("files", "rows", "pos", "eq", "deletes-per-file", "partitions", "seed");

  private static Map<String, String> parse(String[] args) {
    Map<String, String> options = Maps.newHashMap();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("Unexpected argument " + args[i] + "\n" + USAGE);
      }
      String name = args[i].substring(2);
      if (FLAGS.contains(name)) {
        options.put(name, "true");
      } else if (i + 1 < args.length) {
        options.put(name, args[++i]);
      } else {
        throw new IllegalArgumentException("--" + name + " needs a value\n" + USAGE);
      }
    }
    return options;
  }

  private static class WrittenFile {
    private final DataFile file;
    private final List<Long> ids;
    private int cursor = 0;

    private WrittenFile(DataFile file, List<Long> ids) {
      this.file = file;
      this.ids = ids;
    }

    /** Takes the positions of the next {@code count} rows whose id is not touched yet. */
    private List<Integer> takeUntouched(int count, Set<Long> touched) {
      List<Integer> positions = new ArrayList<>(count);
      while (cursor < ids.size() && positions.size() < count) {
        if (touched.add(ids.get(cursor))) {
          positions.add(cursor);
        }
        cursor++;
      }
      return positions;
    }
  }
}
