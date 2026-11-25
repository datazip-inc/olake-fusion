# `io.olake.fusion.io.writer`

Writers that produce the output files of a compaction task: partitioned data files and per-data-file position-delete files.

## Role in Fusion

`IcebergRewriteExecutor` (in `io.olake.fusion.optimizing`) uses `GenericIcebergPartitionedFanoutWriter` from `dataWriter()` for partitioned tables (Iceberg's `UnpartitionedWriter` otherwise) and `IcebergFanoutPosDeleteWriter` from `posWriter()`.

Runs in: **optimizer**.

Mixed-format only (out of scope): `TaskWriterKey` and the `OutputFileFactory` interface here (not Iceberg's `org.apache.iceberg.io.OutputFileFactory`, which `IcebergRewriteExecutor` actually uses). No main-code class implements this package's `OutputFileFactory`; `TaskWriterKey` is used only by `data.FileNameRules`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `GenericIcebergPartitionedFanoutWriter` | `PartitionedFanoutWriter<Record>` that computes the `PartitionKey` of each row through `IcebergRecordWrapper` (UUID-safe copy of `InternalRecordWrapper`). | `org.apache.iceberg.data.IcebergRecordWrapper` |
| `IcebergFanoutPosDeleteWriter<T>` | `FileWriter<PositionDelete<T>, DeleteWriteResult>`. Buffers all `(path, pos, row)` in memory per data file, and on `close()` sorts each list by position and writes one delete file per data file named `<dir>/<dataFileNameWithoutExt>-delete-<suffix>.<ext>`. | `FileAppenderFactory`, `EncryptionManager`, `TableFileUtil.optimizingPosDeleteFileName` |
| `TaskWriterKey`, `OutputFileFactory` | Mixed-format writer key (partition, `DataTreeNode`, `DataFileType`) and the output-file hook keyed by it. | `data.DataTreeNode`, `data.FileNameRules` |

## How it works

- `AbstractRewriteFilesExecutor` writes `readDeletedData()` rows as `PositionDelete`s to `posWriter()`, and `readData()` rows to `dataWriter()`.
- `IcebergFanoutPosDeleteWriter.flushDeletes()` runs once on `close()`; `complete()` closes and returns `completedFiles`; `result()` requires the writer to be closed and returns a `DeleteWriteResult` with referenced data files.
- `IcebergRewriteExecutor` passes `UUID.randomUUID().toString()` as `fileNameSuffix`, so output names are unique per task.

## State, threads and concurrency

Single-threaded, one instance per task. `IcebergFanoutPosDeleteWriter` holds every buffered delete in memory until close, so memory grows with the number of deleted rows in the task.

## Contracts

- Position-delete file naming `<data file>-delete-<suffix>` is relied on by `TableFileUtil.isOptimizingPosDeleteFile`. Changing it changes what the server recognises as an optimizer-written delete file.
- Output files go into the data file's own directory, not the table's location provider.

## Extending or changing it

Keep the per-file sort by position: Iceberg position-delete files must be sorted by `(file_path, pos)`.

## Invariants and pitfalls

- `length()` throws `UnsupportedOperationException`; callers must not use rolling-size logic on this writer.
- A failure in one file's write calls `setFailure` and throws; `complete()` then fails its `checkState`. Files written before the failure are left on storage (cleaned later by orphan cleaning, if enabled).
- Not closing the writer loses all buffered deletes.

## Tests

`fusion-core/src/test/java/io/olake/fusion/io/TestIcebergFanoutPosDeleteWriter.java`; `IcebergDataTestHelpers` uses `GenericIcebergPartitionedFanoutWriter`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestIcebergFanoutPosDeleteWriter -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../reader`](../reader/README.md), [`..`](../README.md)
- [`../../../../../org/apache/iceberg/data`](../../../../../org/apache/iceberg/data/README.md) (`IcebergRecordWrapper`)
- [`../../utils`](../../utils/README.md) (`TableFileUtil`)
