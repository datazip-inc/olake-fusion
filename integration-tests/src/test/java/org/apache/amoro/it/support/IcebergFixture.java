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

import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.HttpClientProperties;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.types.Types;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes and inspects Iceberg tables in the same JDBC catalog that Fusion and OLake use, so each
 * test can build an exact file layout (small files, segment files, equality and position deletes)
 * and read the row set back independently of Fusion.
 *
 * <p>All tables share one schema. {@code id} is the identifier field that equality deletes use, and
 * {@code region} is the identity partition column of {@link Layout#PARTITIONED} tables.
 */
public class IcebergFixture implements AutoCloseable {

  public enum Layout {
    UNPARTITIONED,
    PARTITIONED
  }

  public static final Schema SCHEMA =
      new Schema(
          Arrays.asList(
              Types.NestedField.required(1, "id", Types.LongType.get()),
              Types.NestedField.required(2, "region", Types.StringType.get()),
              Types.NestedField.optional(3, "payload", Types.StringType.get()),
              Types.NestedField.optional(4, "ts", Types.TimestampType.withZone())),
          Collections.singleton(1));

  public static final String[] REGIONS = {"ap", "eu", "us"};

  /**
   * Compaction tests run with a 256 KB target size so real segment files stay small. With the
   * default ratios this gives: fragment files up to 32 KB, undersized segment files 32–192 KB, and
   * target-size-reached files above 192 KB.
   */
  public static final long TARGET_SIZE = 256 * 1024;

  public static final long FRAGMENT_MAX_SIZE = TARGET_SIZE / 8;
  public static final long MIN_TARGET_SIZE = (long) (TARGET_SIZE * 0.75);

  private static final AtomicLong TASK_IDS = new AtomicLong();
  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static IcebergFixture shared;

  private final JdbcCatalog catalog;

  /** One catalog client for the whole run; test classes execute concurrently and share it. */
  public static synchronized IcebergFixture shared() {
    if (shared == null) {
      shared = new IcebergFixture();
    }
    return shared;
  }

  public IcebergFixture() {
    Map<String, String> props = new HashMap<>();
    props.put(CatalogProperties.URI, StackEnv.CATALOG_JDBC_URL);
    props.put("jdbc.user", StackEnv.CATALOG_JDBC_USER);
    props.put("jdbc.password", StackEnv.CATALOG_JDBC_PASSWORD);
    props.put("jdbc.schema-version", "V1");
    props.put(CatalogProperties.WAREHOUSE_LOCATION, StackEnv.WAREHOUSE);
    props.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
    props.put(S3FileIOProperties.ENDPOINT, StackEnv.S3_ENDPOINT);
    props.put(S3FileIOProperties.PATH_STYLE_ACCESS, "true");
    props.put(S3FileIOProperties.ACCESS_KEY_ID, StackEnv.S3_ACCESS_KEY);
    props.put(S3FileIOProperties.SECRET_ACCESS_KEY, StackEnv.S3_SECRET_KEY);
    props.put(AwsClientProperties.CLIENT_REGION, StackEnv.S3_REGION);
    props.put(HttpClientProperties.CLIENT_TYPE, HttpClientProperties.CLIENT_TYPE_URLCONNECTION);
    this.catalog = new JdbcCatalog();
    catalog.initialize(StackEnv.CATALOG, props);
    Namespace namespace = Namespace.of(StackEnv.TEST_DB);
    if (!catalog.namespaceExists(namespace)) {
      try {
        catalog.createNamespace(namespace);
      } catch (org.apache.iceberg.exceptions.AlreadyExistsException ignored) {
        // Another test class created it concurrently.
      }
    }
  }

  /** Unique, lower-case table name: {@code <prefix>_<8 hex chars>}. */
  public static String uniqueName(String prefix) {
    return (prefix + "_" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase();
  }

  // Tables
  /**
   * Creates a format v2 table in {@link StackEnv#TEST_DB}. Self-optimizing crons are unset and
   * dangling-delete cleaning is off, so Fusion leaves the table alone until the test sets the
   * property it is about to verify.
   */
  public Table createTable(String name, Layout layout, Map<String, String> properties) {
    PartitionSpec spec =
        layout == Layout.PARTITIONED
            ? PartitionSpec.builderFor(SCHEMA).identity("region").build()
            : PartitionSpec.unpartitioned();
    Map<String, String> props = new HashMap<>();
    props.put(TableProperties.FORMAT_VERSION, "2");
    props.put(TableProperties.DEFAULT_FILE_FORMAT, "parquet");
    props.put("clean-dangling-delete-files.enabled", "false");
    props.putAll(properties);
    return catalog
        .buildTable(identifier(name), SCHEMA)
        .withPartitionSpec(spec)
        .withProperties(props)
        .create();
  }

  public Table load(String name) {
    return catalog.loadTable(identifier(name));
  }

  public Table load(TableIdentifier identifier) {
    return catalog.loadTable(identifier);
  }

  public List<TableIdentifier> listTables(String namespace) {
    return catalog.listTables(Namespace.of(namespace));
  }

  public List<Namespace> listNamespaces() {
    return catalog.listNamespaces();
  }

  public static TableIdentifier identifier(String name) {
    return TableIdentifier.of(StackEnv.TEST_DB, name);
  }

  public static void setProperties(Table table, Map<String, String> properties) {
    UpdateProperties update = table.updateProperties();
    properties.forEach(update::set);
    update.commit();
  }

  // Rows
  /**
   * Builds {@code count} rows with ids {@code firstId..firstId+count-1}. Regions rotate across
   * {@link #REGIONS}. The payload is random text of {@code payloadChars} characters, so file size
   * grows roughly linearly with the row count.
   */
  public static List<Record> rows(long firstId, int count, int payloadChars, OffsetDateTime ts) {
    Random random = new Random(firstId * 31 + count);
    List<Record> rows = new ArrayList<>(count);
    for (long id = firstId; id < firstId + count; id++) {
      GenericRecord record = GenericRecord.create(SCHEMA);
      record.setField("id", id);
      record.setField("region", REGIONS[(int) (id % REGIONS.length)]);
      StringBuilder payload = new StringBuilder(payloadChars);
      for (int i = 0; i < payloadChars; i++) {
        payload.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
      }
      record.setField("payload", payload.toString());
      record.setField("ts", ts);
      rows.add(record);
    }
    return rows;
  }

  public static List<Record> rows(long firstId, int count) {
    // Iceberg timestamptz keeps microseconds; truncate so expected rows match what is read back.
    return rows(firstId, count, 100, OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
  }

  // Writers. Every writer produces one file per partition touched by the given rows.
  /** A written data file and the rows in it, in file order (row position = list index). */
  public static class WrittenDataFile {
    private final DataFile file;
    private final List<Record> rows;

    WrittenDataFile(DataFile file, List<Record> rows) {
      this.file = file;
      this.rows = rows;
    }

    public DataFile file() {
      return file;
    }

    public List<Record> rows() {
      return rows;
    }
  }

  public List<WrittenDataFile> writeDataFiles(Table table, List<Record> rows) {
    GenericAppenderFactory factory = appenderFactory(table);
    OutputFileFactory files = outputFiles(table);
    List<WrittenDataFile> written = new ArrayList<>();
    for (Map.Entry<StructLike, List<Record>> group : byPartition(table, rows).entrySet()) {
      StructLike partition = group.getKey();
      DataWriter<Record> writer =
          factory.newDataWriter(
              newOutputFile(table, files, partition), FileFormat.PARQUET, partition);
      try {
        for (Record row : group.getValue()) {
          writer.write(row);
        }
      } finally {
        close(writer);
      }
      written.add(new WrittenDataFile(writer.toDataFile(), group.getValue()));
    }
    return written;
  }

  /** Writes the rows (one file per partition) and commits them in one append. */
  public List<WrittenDataFile> append(Table table, List<Record> rows) {
    List<WrittenDataFile> written = writeDataFiles(table, rows);
    org.apache.iceberg.AppendFiles append = table.newAppend();
    written.forEach(w -> append.appendFile(w.file()));
    append.commit();
    return written;
  }

  /** Writes equality deletes on {@code id} for the given rows, one file per partition. */
  public List<DeleteFile> writeEqualityDeletes(Table table, List<Record> rowsToDelete) {
    Schema deleteSchema = table.schema().select("id");
    GenericAppenderFactory factory = appenderFactory(table);
    OutputFileFactory files = outputFiles(table);
    List<DeleteFile> deletes = new ArrayList<>();
    for (Map.Entry<StructLike, List<Record>> group : byPartition(table, rowsToDelete).entrySet()) {
      StructLike partition = group.getKey();
      EqualityDeleteWriter<Record> writer =
          factory.newEqDeleteWriter(
              newOutputFile(table, files, partition), FileFormat.PARQUET, partition);
      try {
        for (Record row : group.getValue()) {
          GenericRecord key = GenericRecord.create(deleteSchema);
          key.setField("id", row.getField("id"));
          writer.write(key);
        }
      } finally {
        close(writer);
      }
      deletes.add(writer.toDeleteFile());
    }
    return deletes;
  }

  /** Writes one position delete file that removes {@code positions} from {@code dataFile}. */
  public DeleteFile writePositionDeletes(Table table, DataFile dataFile, List<Long> positions) {
    GenericAppenderFactory factory = appenderFactory(table);
    StructLike partition = table.spec().isUnpartitioned() ? null : dataFile.partition();
    PositionDeleteWriter<Record> writer =
        factory.newPosDeleteWriter(
            newOutputFile(table, outputFiles(table), partition), FileFormat.PARQUET, partition);
    List<Long> sorted = new ArrayList<>(positions);
    Collections.sort(sorted);
    PositionDelete<Record> delete = PositionDelete.create();
    try {
      for (Long position : sorted) {
        writer.write(delete.set(dataFile.location(), position, null));
      }
    } finally {
      close(writer);
    }
    return writer.toDeleteFile();
  }

  public static void commitDeletes(Table table, Collection<DeleteFile> deletes) {
    org.apache.iceberg.RowDelta rowDelta = table.newRowDelta();
    deletes.forEach(rowDelta::addDeletes);
    rowDelta.commit();
  }

  /** Rows of {@code written} at the given positions. */
  public static List<Record> rowsAt(WrittenDataFile written, List<Long> positions) {
    List<Record> rows = new ArrayList<>();
    for (Long position : positions) {
      rows.add(written.rows().get(position.intValue()));
    }
    return rows;
  }

  public static List<Long> positions(long from, long toExclusive) {
    List<Long> positions = new ArrayList<>();
    for (long p = from; p < toExclusive; p++) {
      positions.add(p);
    }
    return positions;
  }

  /** Puts an object that no snapshot references, under the table's data directory. */
  public static String writeOrphanFile(Table table) {
    String location = table.location() + "/data/orphan-" + UUID.randomUUID() + ".parquet";
    try (PositionOutputStream out = table.io().newOutputFile(location).create()) {
      out.write("not referenced by any snapshot".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return location;
  }

  public static boolean fileExists(Table table, String location) {
    return table.io().newInputFile(location).exists();
  }

  /** Live data and delete files of the current snapshot, read from manifests. */
  public static TableLayout layout(Table table) {
    table.refresh();
    Snapshot snapshot = table.currentSnapshot();
    if (snapshot == null) {
      return new TableLayout(-1, new ArrayList<>(), new ArrayList<>(), new HashMap<>());
    }
    List<DataFile> dataFiles = new ArrayList<>();
    List<DeleteFile> deleteFiles = new ArrayList<>();
    Map<String, String> partitionOf = new HashMap<>();
    for (ManifestFile manifest : snapshot.dataManifests(table.io())) {
      try (ManifestReader<DataFile> reader =
          ManifestFiles.read(manifest, table.io(), table.specs())) {
        for (DataFile file : reader) {
          DataFile copy = file.copy();
          dataFiles.add(copy);
          partitionOf.put(copy.location(), partitionPath(table, copy.specId(), copy.partition()));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    for (ManifestFile manifest : snapshot.deleteManifests(table.io())) {
      try (ManifestReader<DeleteFile> reader =
          ManifestFiles.readDeleteManifest(manifest, table.io(), table.specs())) {
        for (DeleteFile file : reader) {
          DeleteFile copy = file.copy();
          deleteFiles.add(copy);
          partitionOf.put(copy.location(), partitionPath(table, copy.specId(), copy.partition()));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return new TableLayout(snapshot.snapshotId(), dataFiles, deleteFiles, partitionOf);
  }

  /**
   * Delete files in the current snapshot that no data file picks up during a scan. This mirrors the
   * definition Fusion uses in {@code IcebergTableUtil.getDanglingDeleteFiles}.
   */
  public static Set<String> danglingDeleteFiles(Table table) {
    table.refresh();
    Set<String> applied = new HashSet<>();
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        task.deletes().forEach(d -> applied.add(d.location()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Set<String> dangling = new HashSet<>();
    for (DeleteFile file : layout(table).deleteFiles()) {
      if (!applied.contains(file.location())) {
        dangling.add(file.location());
      }
    }
    return dangling;
  }

  /** Current row set, with deletes applied, as sorted {@code id|region|payload|ts} strings. */
  public static List<String> readRows(Table table) {
    table.refresh();
    return collect(IcebergGenerics.read(table).build());
  }

  /**
   * Order-independent digest ({@code count/sum-of-hashes}) of the row set at a snapshot, for tables
   * too large to compare row by row in memory.
   */
  public static String rowDigestAt(Table table, long snapshotId) {
    table.refresh();
    long count = 0;
    long sum = 0;
    try (CloseableIterable<Record> records =
        IcebergGenerics.read(table).useSnapshot(snapshotId).build()) {
      for (Record record : records) {
        count++;
        sum += render(record).hashCode();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return count + "/" + sum;
  }

  /** Live row count with deletes applied. Works for any schema, e.g. tables OLake wrote. */
  public static long countRows(Table table) {
    table.refresh();
    long count = 0;
    try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
      for (Record record : records) {
        if (record != null) {
          count++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return count;
  }

  public static List<String> readRowsAt(Table table, long snapshotId) {
    table.refresh();
    return collect(IcebergGenerics.read(table).useSnapshot(snapshotId).build());
  }

  public static List<String> render(Collection<Record> records) {
    List<String> rendered = new ArrayList<>();
    for (Record record : records) {
      rendered.add(render(record));
    }
    Collections.sort(rendered);
    return rendered;
  }

  /** Expected row set after removing {@code deleted} (matched by id) from {@code all}. */
  public static List<String> expectedRows(List<Record> all, Collection<Record> deleted) {
    Set<Object> deletedIds = new HashSet<>();
    deleted.forEach(r -> deletedIds.add(r.getField("id")));
    List<Record> remaining = new ArrayList<>();
    for (Record record : all) {
      if (!deletedIds.contains(record.getField("id"))) {
        remaining.add(record);
      }
    }
    return render(remaining);
  }

  @Override
  public void close() throws IOException {
    catalog.close();
  }

  // Internals
  private static List<String> collect(CloseableIterable<Record> records) {
    List<String> rows = new ArrayList<>();
    try (CloseableIterable<Record> closeable = records) {
      for (Record record : closeable) {
        rows.add(render(record));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Collections.sort(rows);
    return rows;
  }

  private static String render(Record record) {
    Object ts = record.getField("ts");
    return record.getField("id")
        + "|"
        + record.getField("region")
        + "|"
        + record.getField("payload")
        + "|"
        + (ts == null ? "null" : ((OffsetDateTime) ts).toInstant().toString());
  }

  private static GenericAppenderFactory appenderFactory(Table table) {
    GenericAppenderFactory factory =
        new GenericAppenderFactory(
            table.schema(),
            table.spec(),
            new int[] {table.schema().findField("id").fieldId()},
            table.schema().select("id"),
            null);
    factory.setAll(table.properties());
    return factory;
  }

  private static OutputFileFactory outputFiles(Table table) {
    return OutputFileFactory.builderFor(table, 1, TASK_IDS.incrementAndGet())
        .format(FileFormat.PARQUET)
        .build();
  }

  private static EncryptedOutputFile newOutputFile(
      Table table, OutputFileFactory files, StructLike partition) {
    return partition == null ? files.newOutputFile() : files.newOutputFile(table.spec(), partition);
  }

  /** Groups rows by partition key, keeping row order. The key is null for unpartitioned tables. */
  private static Map<StructLike, List<Record>> byPartition(Table table, List<Record> rows) {
    Map<StructLike, List<Record>> groups = new LinkedHashMap<>();
    if (table.spec().isUnpartitioned()) {
      groups.put(null, new ArrayList<>(rows));
      return groups;
    }
    PartitionKey key = new PartitionKey(table.spec(), table.schema());
    InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
    Map<String, StructLike> keys = new HashMap<>();
    for (Record row : rows) {
      key.partition(wrapper.wrap(row));
      String path = table.spec().partitionToPath(key);
      StructLike partition = keys.computeIfAbsent(path, p -> key.copy());
      groups.computeIfAbsent(partition, p -> new ArrayList<>()).add(row);
    }
    return groups;
  }

  private static String partitionPath(Table table, int specId, StructLike partition) {
    PartitionSpec spec = table.specs().get(specId);
    return spec.isUnpartitioned() ? "" : spec.partitionToPath(partition);
  }

  private static void close(java.io.Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
