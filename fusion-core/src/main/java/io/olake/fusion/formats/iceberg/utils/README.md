# `io.olake.fusion.formats.iceberg.utils`

Helpers for Iceberg table maintenance: snapshot lookup, the sets of files a table still references,
dangling delete detection, and a batched file deleter.

## Role in Fusion

Server side, used by [`IcebergTableMaintainer`](../maintainer/README.md). `fusion-ams` has its own
`io.olake.fusion.server.utils.IcebergTableUtil` with the same helper names
plus the evaluator/planner factories; the two are separate copies.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `IcebergTableUtil` | Static helpers: `getSnapshotId`, `getSnapshot`, `findFirstMatchSnapshot`, `findLatestOptimizingSnapshot`, `getAllContentFilePath`, `getAllStatisticsFilePath`, `getDanglingDeleteFiles`, `getAllManifestFiles`. | `TableEntriesScan`, Iceberg metadata tables |
| `RollingFileCleaner` | Collects file paths (as an Iceberg `deleteWith` callback) and deletes them in groups of `CLEANED_FILE_GROUP_SIZE = 1_000`, then tries to delete empty parent directories. | `AuthenticatedFileIO` |

## How it works

- `getSnapshotId(table, refresh)`: current snapshot id or `Constants.INVALID_SNAPSHOT_ID`; refreshes
  first if asked.
- `findFirstMatchSnapshot(table, predicate)`: walks `table.snapshots()` **newest first** and returns
  the first match.
- `findLatestOptimizingSnapshot(table)`: newest snapshot whose summary contains the value
  `OPTIMIZE` and whose operation is `replace`.
- `getAllContentFilePath(table)`: `TableEntriesScan` over `ALL_ENTRIES` for data, position and
  equality delete files, returning URI paths (`TableFileUtil.getUriPath`). Covers every snapshot
  still in metadata.
- `getAllStatisticsFilePath(table)`: `ReachableFileUtil.statisticsFilesLocations`.
- `getDanglingDeleteFiles(table)`: collects delete paths from `newScan().useSnapshot(current).planFiles()`
  (deletes that apply to at least one live data file), then returns the delete entries of the
  current snapshot (`TableEntriesScan` with `useSnapshot`) whose path is not in that set. On a scan
  `IOException` it logs and returns an empty set (fails safe).
- `getAllManifestFiles(table)`: reads the `path` column of the `ALL_MANIFESTS` metadata table.
- `RollingFileCleaner.addFile(path)`: ignores excluded paths (exact, URI path or parent directory),
  records the path and its parent, and every 1000 files calls `doCleanFiles()` (bulk delete if the
  FileIO supports it, else one by one; failures are logged, not thrown). `clear()` deletes the
  remainder. `fileCount()` / `cleanedFileCount()` report collected and deleted counts.

## State, threads and concurrency

`IcebergTableUtil` is stateless. `RollingFileCleaner` uses concurrent sets and atomic counters because
Iceberg may call `deleteWith` from worker threads; one instance per expiry run.

## Contracts

- Paths are compared as URI paths (scheme and authority stripped); callers must use the same
  normalization.
- `findLatestOptimizingSnapshot` depends on `snapshot.producer = OPTIMIZE` written by
  `UnKeyedTableCommit` and the `replace` operation of `RewriteFiles`.
- Iceberg APIs (1.7.2): `MetadataTableUtils.createMetadataTableInstance`,
  `MetadataTableType.ALL_ENTRIES` / `ALL_MANIFESTS` / `ENTRIES`, `ReachableFileUtil`,
  `SupportsBulkOperations`, `BulkDeletionFailureException`.

## Extending or changing it

Keep "valid file" sets computed over **all** snapshots; computing them on the current snapshot only
would make orphan cleaning delete files that older snapshots, tags or branches still reference.

## Invariants and pitfalls

- A bulk delete failure only logs `numberFailedObjects()`; the cleaned counter is not updated for
  that group, and failed files remain on storage.
- `RollingFileCleaner.isFileExcluded` uses `new Path(uriPath).getParent()`, which assumes the path has
  a parent.
- All `CloseableIterable`s here are closed with try-with-resources.

## Tests

Covered indirectly by `fusion-ams/src/test/java/io/olake/fusion/server/optimizing/maintainer/`
(`TestOrphanFileCleanIceberg`, `TestSnapshotExpireIceberg`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestOrphanFileCleanIceberg -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../maintainer`](../maintainer/README.md): consumer.
- [`../../../scan`](../../../scan/README.md): `TableEntriesScan`.
