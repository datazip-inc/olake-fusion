# `io.olake.fusion.io`

File access for Fusion tables: an `org.apache.iceberg.io.FileIO` extension that can run calls as an authenticated user, plus small helpers for closing iterators and a (disabled) table trash.

## Role in Fusion

Every table Fusion loads gets an `AuthenticatedFileIO`. `IcebergTable.newIcebergTable` (in `io.olake.fusion.formats.iceberg`) calls `AuthenticatedFileIOs.buildAdaptIcebergFileIO(metaStore, icebergTable.io())`, and the result is what `MixedTable.io()` returns for the rest of the code base: planning and table maintenance in the server, and reading and writing data in the optimizer.

Runs in: **both**. The server uses it for planning, commit, maintenance (`TableFileUtil.deleteFiles`, orphan cleaning) and the dashboard. The optimizer uses it inside the rewrite executors (`io.olake.fusion.optimizing.IcebergRewriteExecutor`) and the readers in `io.reader`. Because `RewriteFilesInput` carries the `MixedTable` to the optimizer by Java serialization, the FileIO travels with the task.

For Fusion's Iceberg catalogs (Glue, REST, custom JDBC, Hive) the table's FileIO is normally `S3FileIO` or another non-Hadoop FileIO, so the class actually used is `AuthenticatedFileIOAdapter`. `AuthenticatedHadoopFileIO` is used only when the catalog hands back a `HadoopFileIO`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `AuthenticatedFileIO` | Interface extending `FileIO` with `doAs(Callable)`, `exists(String)` and capability checks/casts: `supportPrefixOperations()`/`asPrefixFileIO()`, `supportBulkOperations()`/`asBulkFileIO()`, `supportFileSystemOperations()`/`asFileSystemIO()`, `supportsFileRecycle()`/`asFileRecycleIO()`. | Iceberg `SupportsPrefixOperations`, `SupportsBulkOperations` |
| `AuthenticatedFileIOAdapter` | Wraps any `FileIO`. `doAs` just calls the callable (or delegates if the wrapped IO is itself an `AuthenticatedFileIO`). Capabilities are answered by `instanceof` on the wrapped IO. `exists` maps `NotFoundException` to `false`. | Wrapped `FileIO` (for example `S3FileIO`) |
| `AuthenticatedFileIOs` | Factory: `buildAdaptIcebergFileIO`, `buildHadoopFileIO`, `buildRecoverableHadoopFileIO`. | `TableMetaStore`, `TableTrashManagers` |
| `AuthenticatedHadoopFileIO` | `HadoopFileIO` subclass; every operation runs inside `TableMetaStore.doAs`. Implements prefix, bulk and file-system operations (`listDirectory`, `makeDirectories`, `isDirectory`, `isEmptyDirectory`, `rename`). Constructor is package-private. | `TableMetaStore`, Hadoop `FileSystem` |
| `RecoverableHadoopFileIO` | `AuthenticatedHadoopFileIO` whose `deleteFile` moves files that match `table-trash.file-pattern` into the trash instead of deleting them. | `TableTrashManager` |
| `SupportsFileSystemOperations` | Mixin interface for directory operations. | — |
| `SupportFileRecycleOperations` | Mixin interface: `fileRecoverable`, `recover`, `expireRecycle(LocalDate)`. | — |
| `TableTrashManager`, `BasicTableTrashManager`, `TableTrashManagers` | Trash layout `<trash>/<yyyyMMdd>/<path relative to table root>`; default trash dir `.trash` under the table location or under `table-trash.custom-root-location/<catalog>/<db>/<table>`. | `TableFileUtil` |
| `PathInfo` | `FileInfo` plus `isDirectory()`. | — |
| `CloseableIterableWrapper`, `CloseableIteratorWrapper` | Attach extra `Closeable`s to an iterable/iterator; closed when the iterator is closed. Used by `io.reader.DeleteFilter` to free equality-delete sets. | — |
| `CloseablePredicate` | A `Predicate` that owns `Closeable` resources (struct maps that may spill to RocksDB). | `io.reader.CombinedDeleteFilter`, `io.reader.DeleteFilter` |

## How it works

- **Building the IO.** `AuthenticatedFileIOs.buildAdaptIcebergFileIO(tableMetaStore, io)` returns `new AuthenticatedHadoopFileIO(tableMetaStore)` if `io instanceof HadoopFileIO`, otherwise `new AuthenticatedFileIOAdapter(io)`. The wrapped Iceberg table is then rebuilt with `MixedFormatCatalogUtil.useMixedTableOperations` so table operations also use this IO.
- **Authentication.** `AuthenticatedHadoopFileIO` routes each call through `TableMetaStore.doAs`, which runs it as the Kerberos or SIMPLE user (or directly when auth is disabled or `ak/sk`). The adapter does no impersonation; S3/Glue credentials come from the wrapped FileIO's own properties.
- **Trash.** `buildRecoverableHadoopFileIO` would build a `RecoverableHadoopFileIO` when `table-trash.enabled` is true, but the guard is `!CLOSE_TRASH && ...` and `AuthenticatedFileIOs.CLOSE_TRASH = true`. The trash path is therefore never taken; a plain `AuthenticatedHadoopFileIO` is returned.

## State, threads and concurrency

The FileIO objects hold no mutable state of their own beyond what Hadoop `FileSystem` caches or the wrapped FileIO hold. They are shared between threads in the server (planning pool, maintenance executors) and must stay thread-safe. `BasicTableTrashManager` is not synchronised; concurrent `moveFileToTrash` calls for the same path can race on the exists/delete/rename sequence.

## Contracts

- `AuthenticatedFileIO` is `Serializable` (through `FileIO`) and is serialized into every optimizing task (inside `RewriteFilesInput` → `BasicUnkeyedTable`). Server and optimizer must run the same build.
- Trash directory layout: `<trashLocation>/<yyyyMMdd>/<relative path>` (`BasicTableTrashManager.generateFileLocationInTrash`), date in the JVM default zone.

## Extending or changing it

- New storage capability: add a mixin interface like `SupportsFileSystemOperations`, a `supportX()`/`asX()` pair on `AuthenticatedFileIO`, and delegation in `AuthenticatedFileIOAdapter` (it answers by `instanceof` on the wrapped IO).
- Code that needs directory listing must check `supportFileSystemOperations()` first: with `S3FileIO` behind the adapter it returns `false`, and `asFileSystemIO()` throws.

## Invariants and pitfalls

- `asFileSystemIO()` on the adapter fails a `Preconditions.checkArgument` for S3; `TableFileUtil.deleteEmptyDirectory` calls it without a check and only works on Hadoop-style IO.
- `RecoverableHadoopFileIO.matchTrashFilePattern` dereferences `pattern`, which is `null` when the pattern string is empty. Dead code today because `CLOSE_TRASH` is `true`; it matters if trash is ever enabled.
- Iterators returned by readers must be closed; `CloseableIteratorWrapper.close` closes all attached resources, logs failures and throws one `IOException` at the end.
- `AuthenticatedHadoopFileIO.isDirectory` builds its error message with a literal `%s` that is never formatted.

## Tests

`fusion-core/src/test/java/io/olake/fusion/io/`: `TestRecoverableAuthenticatedFileIO`, `TestBasicTableTrashManager`, `TestTableTrashManagers`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestTableTrashManagers -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`reader`](reader/README.md), [`writer`](writer/README.md)
- [`../table`](../table/README.md) (`TableMetaStore`, `TableProperties`)
- [`../utils`](../utils/README.md) (`TableFileUtil`, `MixedFormatCatalogUtil`)
