# `io.olake.fusion.io.reader`

Readers that turn the input files of one optimizing task into live rows (deletes applied) and deleted rows (to be rewritten as position deletes).

## Role in Fusion

This is the read half of compaction. `IcebergRewriteExecutor.dataReader()` (in `io.olake.fusion.optimizing`) builds a `GenericCombinedIcebergDataReader` for each task; `AbstractRewriteFilesExecutor` writes what it returns with the writers in [`../writer`](../writer/README.md).

Runs in: **optimizer** (Spark executor JVMs in production, the standalone optimizer for local debugging). `GenericUnkeyedDataReader` is also used by tests and referenced from `io.olake.fusion.scan.CombinedIcebergScanTask`'s javadoc.

Mixed-format only (out of scope): the `MixedFileScanTask` branches in `DataReaderCommon.getIdToConstant(FileScanTask, ...)`, the `NodeFilter`/`DataTreeNode` path of `AbstractUnkeyedDataReader`, and the `_tree_node`/`_transaction_id` metadata columns when they come from mixed file names.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `GenericCombinedIcebergDataReader` | Implements `OptimizingDataReader`. `readData()` reads `RewriteFilesInput.rewrittenDataFiles()` and keeps rows **not** deleted; `readDeletedData()` reads `rePosDeletedDataFiles()` and keeps only rows that **are** deleted (file path, row position, tree node). Decrypts through the table's `EncryptionManager`. Supports Avro, Parquet, ORC. | `CombinedDeleteFilter`, `StructForDelete`, `DataReaderCommon` |
| `CombinedDeleteFilter<T>` | Loads every delete file of the task once and applies all of them to all data files of the task. Position deletes go into a `Roaring64Bitmap` per data file path; equality deletes go into a `StructLikeBaseMap<Long>` (key → max delete data sequence number) per equality-field set. | `StructLikeCollections`, `BaseDeleteLoader`, `DeleteCache`, Guava `BloomFilter` |
| `StructForDelete<T>` | Reusable view over a record: `getPk()` (projection to delete ids), `getLsn()` (`_transaction_id`), `getPosition()` (`_pos`), `filePath()` (`_file`), `recover()`. | Iceberg `StructProjection`, `Accessor` |
| `DeleteCache` | JVM-wide Caffeine cache of loaded equality deletes, keyed `group + "_" + key`, group = process id. Configured by system properties `delete-cache-enabled` (default `false`), `delete-cache-max-entry-size` (`64mb`), `delete-cache-max-total-size` (`128mb`), `delete-cache-timeout` (`10min`). | `MemorySize`, `ConfigHelpers` |
| `AbstractUnkeyedDataReader<T>`, `GenericUnkeyedDataReader` | Per-`FileScanTask` reader (Parquet/ORC) using `DeleteFilter`. Not on the production compaction path. | `DeleteFilter`, `NodeFilter` |
| `DeleteFilter<T>` | Copy of Iceberg's `org.apache.iceberg.data.DeleteFilter` that caches the position set and equality predicates for reuse and drops the merge-sort position path. | `Deletes`, `StructLikeSet` |
| `Deletes` | Copy of Iceberg's `org.apache.iceberg.deletes.Deletes` (`filter`, `toEqualitySet`, `toPositionSet`). | — |
| `DataReaderCommon` | Builds the `idToConstant` map: identity-partition values, `_file`, and `_transaction_id` (= `dataFile.dataSequenceNumber()` for the `DataFile` overload). | Iceberg `PartitionUtil` |
| `StructLikeFunnel` | Guava `Funnel` that Kryo-serializes a struct copy, for the bloom filter. | `SerializationUtil`, `StructLikeWrapperSerializer.StructLikeCopy` |

## How it works

1. **Construction.** `CombinedDeleteFilter` sorts `rewriteFilesInput.deleteFiles()` into position deletes and equality deletes (grouped by `equalityFieldIds()`), computes `deleteIds()` as the union of all equality field ids, and `requiredSchema = TypeUtil.select(tableSchema, deleteIds)`. It picks `CachingDeleteLoader` when the system property `delete-cache-enabled` is `true`, else Iceberg's `BaseDeleteLoader`.
2. **Projection.** `GenericCombinedIcebergDataReader.fileProjection` adds the missing equality columns, then `_file`, `_pos` (when there are position deletes), `_transaction_id` and `_deleted` at the end.
3. **`_transaction_id` is the data sequence number.** `DataReaderCommon.getIdToConstant(DataFile, ...)` sets it to `dataFile.dataSequenceNumber()`. An equality delete removes a row only if `deleteLsn > dataLSN` (`applyEqDeletesForSchema`), which is Iceberg's rule that an equality delete applies to data with a strictly lower sequence number.
4. **Position deletes.** `applyPosDeletes()` reads all position-delete files once, keeping only entries whose path is one of the task's data files (`positionPathSets`).
5. **Equality deletes.** `openEqualityDeletes` calls Iceberg's private `BaseDeleteLoader.getOrReadEqDeletes(DeleteFile, Schema)` through `DynMethods` (`READ_EQ_DELETES_METHOD`), so cached and non-cached loads share Iceberg's code. If the total equality-delete record count exceeds `FILTER_EQ_DELETE_TRIGGER_RECORD_COUNT` (1,000,000), `initializeBloomFilter()` first reads the key columns of all `dataFiles()` into a bloom filter (fpp 0.001) and skips delete keys that cannot match.
6. **Filtering.** `filter(...)` = drop rows matched by position or equality deletes; `filterNegate(...)` = keep only those rows.
7. **Close.** `GenericCombinedIcebergDataReader.close()` → `CombinedDeleteFilter.close()` drops the bitmap and closes `eqPredicate`, which closes the struct maps (and their RocksDB column families when spilling).

## State, threads and concurrency

- One reader per task, used by one thread. `CombinedDeleteFilter` caches `positionMap` and `eqPredicate` lazily; not thread-safe.
- `DeleteCache` is a per-JVM singleton (`getInstance()`, double-checked init). In Spark, executor JVMs are reused across tasks, so the first task's system properties fix the cache size and timeout for the JVM's life.
- `OptimizerExecutor.fillTaskProperties` (in `fusion-optimizer-common`) sets the cache system properties per task.

## Contracts

- `StructLikeCollections` (from `TaskProperties.getStructLikeCollections`: `extend-disk-storage`, memory size default 512 MB, disk path) decides whether equality-delete maps spill to RocksDB. See [`../../utils/map`](../../utils/map/README.md).
- Supported formats: data and delete files in Avro, Parquet, ORC. `AbstractUnkeyedDataReader` supports only Parquet and ORC.

## Extending or changing it

- A new record type: subclass `CombinedDeleteFilter` (implement `getInputFile`, `getFileIO`) and write a reader like `GenericCombinedIcebergDataReader`.
- Keep delete semantics equal to Iceberg's: sequence-number comparison for equality deletes, path+position for position deletes. A wrong comparison loses or resurrects rows.

## Invariants and pitfalls

- **Iceberg version coupling.** `READ_EQ_DELETES_METHOD` is resolved in a static initializer by the name `getOrReadEqDeletes` with `(DeleteFile, Schema)`. If an Iceberg upgrade renames it or changes its signature, `DynMethods.Builder.build()` throws when the class loads and every task fails. `CachingDeleteLoader` also overrides `BaseDeleteLoader.canCache(long)` and `getOrLoad(String, Supplier, long)` (protected in 1.7.2). Recheck all three on upgrade.
- `CombinedDeleteFilter`'s javadoc says equality deletes "only be written by flink"; OLake also writes equality deletes. The code itself handles any equality field set.
- `DeleteCache.getOrLoad` evicts `groups.remove(groups.size() - 1)`, the most recently added other group, not the oldest. `findInternalKeys` matches by `startsWith(group)`, so invalidating group `12` would also drop keys of group `123`. Nothing in the tree calls `invalidate` today.
- Bug outside this package: `OptimizerExecutor.fillTaskProperties` sets `DeleteCache.DELETE_CACHE_MAX_TOTAL_SIZE_DEFAULT` as the property **key** for the total size, and compares the timeout with `DELETE_CACHE_TIMEOUT` (the key) instead of the default. A configured max total size is never applied.
- Position-delete iterators in `applyPosDeletes()` are closed by Iceberg's concat iterator only when exhausted; an exception mid-read leaks the open files until GC.
- `StructForDelete` reuses one wrapper object per iterable; do not hold the wrapped record past the next `next()` unless `reuseContainer` is false (it is `false` in `IcebergRewriteExecutor`).

## Tests

`fusion-core/src/test/java/io/olake/fusion/io/TestIcebergCombinedReader.java`, `TestIcebergCombinedReaderVariousTypes.java` (bloom filter via `FILTER_EQ_DELETE_TRIGGER_RECORD_COUNT`, delete cache). Mixed-format test readers live in `src/test/java/io/olake/fusion/io/reader/`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestIcebergCombinedReader -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../writer`](../writer/README.md), [`..`](../README.md)
- [`../../utils/map`](../../utils/map/README.md) (spillable maps), [`../../utils`](../../utils/README.md) (`DynMethods`, `MemorySize`)
- [`../../table`](../../table/README.md) (`MetadataColumns`)
