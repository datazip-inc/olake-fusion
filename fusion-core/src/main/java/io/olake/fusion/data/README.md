# `io.olake.fusion.data`

File-level types inherited from Amoro's mixed format: file types, the hash tree node, file-name parsing and a `DataFile` wrapper.

## Role in Fusion

Most of this package exists for mixed-format keyed tables (change store, base store, tree nodes), which are out of scope. It still sits on an Iceberg path in one place:

- `DataFileType.ofContentId(...)` maps Iceberg `FileContent` ids to `BASE_FILE`, `POS_DELETE_FILE` or `EQ_DELETE_FILE` for the dashboard (`fusion-ams` `MixedAndIcebergTableDescriptor`), which is how file types appear in REST responses.

The other types are still referenced by shared reader, writer and scan code (`io.reader.AbstractUnkeyedDataReader` / `GenericUnkeyedDataReader` take an optional `Set<DataTreeNode>`, `io.reader.DataReaderCommon` reads `MixedFileScanTask` metadata, `io.writer.TaskWriterKey`, `scan.MixedFileScanTask`, `utils.NodeFilter`), but Iceberg compaction does not go through those branches.

Runs in: **both** (server dashboard; the shared reader code is on the optimizer classpath).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `DataFileType` | `BASE_FILE`, `INSERT_FILE`, `EQ_DELETE_FILE`, `POS_DELETE_FILE`, `ICEBERG_EQ_DELETE_FILE`, `LOG_FILE`, with id and short name. `ofContentId(Integer)` (null → `BASE_FILE` for v1 tables), `ofId`, `ofShortName`. | Iceberg `FileContent` |
| `DefaultKeyedFile` | `PrimaryKeyedFile`/`Serializable` wrapper around a `DataFile` plus `FileMeta(transactionId, type, node)`, built with `parseBase` / `parseChange`. `equals`/`hashCode` by path. | `FileNameRules` |
| `FileNameRules` | Mixed file name format `${node}-${type}-${txId}-${partition}-${task}-${opId}-${count}` (`fileName(TaskWriterKey)`) and its parsers (`parseBase`, `parseChange`, `parseFileType*`, `parseFileNodeFromFileName`, `parseTransactionId`, `parseChangeTransactionId`). | `TableFileUtil`, `IdGenerator`, `Constants.INNER_TABLE_BASE` / `INNER_TABLE_CHANGE` |
| `DataTreeNode` | `(mask, index)` hash bucket node; `ROOT = (0,0)`. | `utils.NodeFilter`, `io.writer.TaskWriterKey` |
| `PrimaryKeyData`, `PrimaryKeyedFile`, `ChangeAction` | Mixed-format primary key tuple, keyed file interface, change-log row kind. Out of scope. | `table.PrimaryKeySpec`, `scan.MixedFileScanTask`, `io.reader.DataReaderCommon` |

## How it works

`FileNameRules.parseBase(path)` matches `KEYED_FILE_NAME_PATTERN` (`\.?(\d+)-(\w+)-(\d+)-(\d+)-(\d+)-.*`) and a valid short name; otherwise it returns `DEFAULT_BASE_FILE_META` (transaction id 0, `BASE_FILE`, `DataTreeNode.ROOT`). A file whose name happened to match the pattern would get a non-root node and a transaction id taken from its name.

## State, threads and concurrency

Immutable value types. `FileNameRules` instances hold an `AtomicLong` counter; parsers are static.

## Contracts

- `DataFileType` names (`BASE_FILE`, `POS_DELETE_FILE`, `EQ_DELETE_FILE`) are returned as strings in dashboard file listings (`PartitionFileBaseInfo.fileType`).
- `DefaultKeyedFile`, `DefaultKeyedFile.FileMeta`, `DataTreeNode` and `PrimaryKeyData` are `Serializable` without an explicit `serialVersionUID`; any field or signature change changes their serialized form.

## Extending or changing it

Do not add Iceberg logic here. Changing `DataFileType` names changes dashboard REST responses.

## Invariants and pitfalls

- `DefaultKeyedFile.fileSequenceNumber()` returns `internalFile.dataSequenceNumber()`, not the file sequence number.
- `DefaultKeyedFile` does not override `content()`, `equalityFieldIds()` or `sortOrderId()`; it relies on `DataFile` defaults.
- `DataFileType.ofContentId` maps Iceberg equality deletes to `EQ_DELETE_FILE`, not `ICEBERG_EQ_DELETE_FILE`.

## Tests

`fusion-core/src/test/java/io/olake/fusion/io/TestFileNameGenerator.java`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestFileNameGenerator -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../io/reader`](../io/reader/README.md), [`../table`](../table/README.md), [`../utils`](../utils/README.md)
