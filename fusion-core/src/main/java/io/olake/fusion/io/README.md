# `io.olake.fusion.io`

File access for Fusion tables: an `org.apache.iceberg.io.FileIO` extension with capability checks and a `doAs` hook (calls run as the process user), plus small helpers for closing iterators and a (disabled) table trash.

## Role in Fusion

Every table Fusion loads gets an `AuthenticatedFileIO`. `IcebergTable.newIcebergTable` (in `io.olake.fusion.formats.iceberg`) calls `AuthenticatedFileIOs.buildAdaptIcebergFileIO(icebergTable.io())`, and the result is what `MixedTable.io()` returns for the rest of the code base: planning and table maintenance in the server, and reading and writing data in the optimizer.

Runs in: **both**. The server uses it for planning, commit, maintenance (`TableFileUtil.deleteFiles`, orphan cleaning) and the dashboard. The optimizer uses it inside the rewrite executors (`io.olake.fusion.optimizing.IcebergRewriteExecutor`) and the readers in `io.reader`. Because `RewriteFilesInput` carries the `MixedTable` to the optimizer by Java serialization, the FileIO travels with the task.

The class used is always `AuthenticatedFileIOAdapter`, around whatever FileIO the catalog gives the table. Glue and REST catalogs pick an S3-capable FileIO themselves. For `hive` and `custom` catalogs (custom JDBC) without an `io-impl`, `CatalogUtil.applyDefaultFileIO` sets `io-impl=org.apache.iceberg.io.ResolvingFileIO`, which uses `S3FileIO` for `s3`, `s3a` and `s3n` paths and `HadoopFileIO` for anything else. Without it, Iceberg's `HiveCatalog` and `JdbcCatalog` default to `HadoopFileIO`, which cannot read `s3://` paths.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `AuthenticatedFileIO` | Interface extending `FileIO` with `doAs(Callable)`, `exists(String)` and capability checks/casts: `supportPrefixOperations()`/`asPrefixFileIO()`, `supportBulkOperations()`/`asBulkFileIO()`, `supportsFileRecycle()`/`asFileRecycleIO()`. | Iceberg `SupportsPrefixOperations`, `SupportsBulkOperations` |
| `AuthenticatedFileIOAdapter` | Wraps any `FileIO`. `doAs` just calls the callable (or delegates if the wrapped IO is itself an `AuthenticatedFileIO`). Capabilities are answered by `instanceof` on the wrapped IO. `exists` maps `NotFoundException` to `false`. | Wrapped `FileIO` (for example `S3FileIO`) |
| `AuthenticatedFileIOs` | Factory: `buildAdaptIcebergFileIO(FileIO)`, which always returns an `AuthenticatedFileIOAdapter`. | `AuthenticatedFileIOAdapter` |
| `SupportFileRecycleOperations` | Mixin interface: `fileRecoverable`, `recover`, `expireRecycle(LocalDate)`. | — |
| `TableTrashManager`, `BasicTableTrashManager`, `TableTrashManagers` | Trash layout `<trash>/<yyyyMMdd>/<path relative to table root>`; default trash dir `.trash` under the table location or under `table-trash.custom-root-location/<catalog>/<db>/<table>`. | `TableFileUtil` |
| `CloseableIterableWrapper`, `CloseableIteratorWrapper` | Attach extra `Closeable`s to an iterable/iterator; closed when the iterator is closed. Used by `io.reader.DeleteFilter` to free equality-delete sets. | — |
| `CloseablePredicate` | A `Predicate` that owns `Closeable` resources (struct maps that may spill to RocksDB). | `io.reader.CombinedDeleteFilter`, `io.reader.DeleteFilter` |

## How it works

- **Building the IO.** `AuthenticatedFileIOs.buildAdaptIcebergFileIO(io)` returns `new AuthenticatedFileIOAdapter(io)`. Prefix and bulk operations are available exactly when the wrapped FileIO implements them; `ResolvingFileIO`, `S3FileIO` and `HadoopFileIO` implement both. The wrapped Iceberg table is then rebuilt with `MixedFormatCatalogUtil.useMixedTableOperations` so table operations also use this IO.
- **Authentication.** There is no impersonation: `AuthenticatedFileIOAdapter.doAs` runs the callable directly (or delegates when the wrapped IO is itself an `AuthenticatedFileIO`). S3/Glue credentials come from the wrapped FileIO's own properties.

## State, threads and concurrency

The FileIO objects hold no mutable state of their own beyond what the wrapped FileIO holds. They are shared between threads in the server (planning pool, maintenance executors) and must stay thread-safe. `BasicTableTrashManager` is not synchronised; concurrent `moveFileToTrash` calls for the same path can race on the exists/delete/rename sequence.

## Contracts

- `AuthenticatedFileIO` is `Serializable` (through `FileIO`) and is serialized into every optimizing task (inside `RewriteFilesInput` → `BasicUnkeyedTable`). Server and optimizer must run the same build.
- Trash directory layout: `<trashLocation>/<yyyyMMdd>/<relative path>` (`BasicTableTrashManager.generateFileLocationInTrash`), date in the JVM default zone.

## Extending or changing it

- New storage capability: add a mixin interface like `SupportFileRecycleOperations`, a `supportX()`/`asX()` pair on `AuthenticatedFileIO`, and delegation in `AuthenticatedFileIOAdapter` (it answers by `instanceof` on the wrapped IO).
- Code that lists files must check `supportPrefixOperations()` first; there are no directory operations.

## Invariants and pitfalls

- Iterators returned by readers must be closed; `CloseableIteratorWrapper.close` closes all attached resources, logs failures and throws one `IOException` at the end.

## Tests

`fusion-core/src/test/java/io/olake/fusion/io/`: `TestRecoverableAuthenticatedFileIO`, `TestBasicTableTrashManager`, `TestTableTrashManagers`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestTableTrashManagers -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`reader`](reader/README.md), [`writer`](writer/README.md)
- [`../table`](../table/README.md) (`TableMetaStore`, `TableProperties`)
- [`../utils`](../utils/README.md) (`TableFileUtil`, `MixedFormatCatalogUtil`)
