# `org.apache.iceberg` (shim in fusion-core)

`IcebergFindFiles`, a Fusion class placed in Iceberg's own package so it can use Iceberg's package-private manifest API.

## Role in Fusion

The dashboard lists table files and partitions with it: `fusion-ams` `MixedAndIcebergTableDescriptor` calls `new IcebergFindFiles(table).ignoreDeleted().planWith(executorService)` (and `inPartitions(...)` for one partition) to serve `.../partitions` and `.../partitions/{partition}/files`.

Runs in: **server** only.

## Why it lives in this package

It is like Iceberg's `FindFiles`, but can also scan delete manifests (`fileContent(ManifestContent)`), filter by partitions per spec (`inPartitions`), and returns the status and snapshot id of each entry (`IcebergManifestEntry`). To do that it needs Iceberg internals that are not public in 1.7.2:

- `ManifestEntry` (package-private interface) and `ManifestEntry.Status`;
- `ManifestFiles.open(ManifestFile, FileIO, Map<Integer, PartitionSpec>)` (package-private);
- `ManifestReader.entries()` and `liveEntries()` (package-private) and `ManifestReader.ALL_COLUMNS` (package-private constant);
- `GenericDataFile` (package-private class) and `DataFile.getType(...)`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `IcebergFindFiles` | Builder: `inSnapshot`, `asOfTime`, `filterData`, `filterFiles`, `includeColumnStats`, `fileContent`, `inPartitions`, `filterManifests`, `filterManifestEntries`, `scanMetrics`, `ignoreDeleted`, `ignoreExisting`, `select`, `caseSensitive`, `planWith`; `entries()` returns `CloseableIterable<IcebergManifestEntry>`. | `ManifestFiles`, `ManifestReader`, `ManifestEvaluator`, `ParallelIterable` |
| `IcebergFindFiles.IcebergManifestEntry` | `getFile()` (a copy of the `ContentFile`, stats only if requested), `getStatus()` (`EXISTING`/`ADDED`/`DELETED`), `getSnapshotId()`. | — |

## How it works

`entries()` picks the snapshot (given or current), selects all/data/delete manifests, prunes manifests with a per-spec `ManifestEvaluator` built from the partition filter and the projected data filter, optionally drops manifests with no live (or no added/deleted) files, opens each surviving manifest with `ManifestFiles.open(...)`, and copies each entry's file. With `planWith(executor)` manifests are read in parallel through `ParallelIterable`.

## State, threads and concurrency

One builder per query; not thread-safe. Parallelism comes only from the given executor.

## Contracts

None beyond the Java API. Returned files are copies and can be held after the iterable is closed.

## Extending or changing it

Keep additions minimal; every new package-private dependency adds upgrade risk. Prefer public Iceberg APIs (`TableScan`, metadata tables) when they suffice.

## Invariants and pitfalls

- **Iceberg upgrade risk.** Package-private members can change in any Iceberg release without notice. When Fusion moves past 1.7.2, compile this class first and compare `ManifestFiles.open`, `ManifestReader.entries/liveEntries`, `ManifestEntry` and `GenericDataFile` against the new tag (`git -C <iceberg> show apache-iceberg-<version>:core/src/main/java/org/apache/iceberg/<File>.java`).
- Split packages: this class must be on the same classloader as Iceberg's `org.apache.iceberg` classes. A sealed or modular Iceberg jar, or a different classloader in the Spark optimizer, would cause `IllegalAccessError` (today it is used only in the server).
- `filterFiles(...)` evaluates entries with `evaluator.eval((GenericDataFile) entry.file())`. Delete-manifest entries are `GenericDeleteFile`, so combining `filterFiles` with delete manifests (`fileContent(DELETES)` or the default all-manifests mode on a table with deletes) throws `ClassCastException`. No caller in the tree sets `filterFiles` today.
- Always close the iterable from `entries()`; it holds open manifest readers.

## Tests

`fusion-core/src/test/java/org/apache/iceberg/TestIcebergFindFiles.java` (parameterised on format version 1 and 2).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestIcebergFindFiles -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`data`](data/README.md)
- [`../../../io/olake/fusion/table/descriptor`](../../../io/olake/fusion/table/descriptor/README.md)
