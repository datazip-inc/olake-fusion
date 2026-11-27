# `io.olake.fusion.server.optimizing.maintainer`

Builds the table maintainers that the maintenance executors use (snapshot expiry, orphan-file
cleaning, dangling-delete cleaning, data expiry, tag creation), and gives them the server state they
need.

## Role in Fusion

This package is not on the compaction path itself; it runs next to it. The maintenance executors
in [`../../scheduler/inline`](../../scheduler/inline/README.md) (`SnapshotsExpiringExecutor`,
`OrphanFilesCleaningExecutor`, `DanglingDeleteFilesCleaningExecutor`, `DataExpiringExecutor`,
`TagsAutoCreatingExecutor`) call `TableMaintainers.create(amoroTable, tableRuntime)` on each run
and then one `TableMaintainer` method (`expireSnapshots`, `cleanOrphanFiles`,
`cleanDanglingDeleteFiles`, `expireData`, `autoCreateTags`). The maintainer itself,
`IcebergTableMaintainer`, is in `fusion-core` (`io.olake.fusion.formats.iceberg.maintainer`).

The link to compaction is `OptimizingInfo`: while a run is in flight, the maintainer asks for the
run's target snapshot so it can keep it (`IcebergTableMaintainer.fetchOptimizingPlanSnapshotTime`).
The commit needs that snapshot (see [`../README.md`](../README.md)).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TableMaintainerFactory` | `create(FusionTable, TableRuntime)`: an `IcebergTableMaintainer` for `ICEBERG` tables; `createIcebergMaintainer(Table, DefaultTableRuntime)`. | `IcebergTableMaintainer`, `DefaultTableMaintainerContext` |
| `TableMaintainers` | `@Deprecated` ("since 0.9.0, will be removed in 0.10.0"), but it is what every executor still calls. Same dispatch, delegating to `TableMaintainerFactory`. | `TableMaintainerFactory` |
| `DefaultTableMaintainerContext` | `TableMaintainerContext` over a `DefaultTableRuntime`: table configuration, orphan-cleaning metrics, `OptimizingInfo`. | `DefaultTableRuntime`, `TableOrphanFilesCleaningMetrics` |
| `DefaultTableMaintainerContext.DefaultOptimizingInfo` (private) | `isProcessing()` = table status `isProcessing()`; `getTargetSnapshotId()` = `getOptimizingProcess().getTargetSnapshotId()`, or `Long.MAX_VALUE` when not processing. | `OptimizingProcess` |

## How it works

1. An executor thread calls `TableMaintainers.create(amoroTable, tableRuntime)`. The overload
   that takes `DefaultTableRuntime` is chosen when the static type allows it; the
   `TableRuntime` one checks `instanceof DefaultTableRuntime` with `Preconditions`.
2. `TableFormat.ICEBERG` → `IcebergTableMaintainer(table, identifier, new
   DefaultTableMaintainerContext(runtime))`. Any other format throws
   `RuntimeException("Unsupported table type...")`.
3. The maintainer reads per-table settings from `getTableConfiguration()` and reports orphan
   cleaning through `getMetrics()` → `recordOrphanDataFilesCleaned` /
   `recordOrphanMetadataFilesCleaned` → `TableOrphanFilesCleaningMetrics.completeOrphanDataFiles`
   / `completeOrphanMetadataFiles`.

## State, threads and concurrency

No state of its own; a new context is made per run. It runs on the executor's thread
(`async-<executor-name>-%d`), concurrently with the Thrift and commit threads that change the
table status. `DefaultOptimizingInfo` reads the status and then the process with no lock.
`DefaultTableRuntime.completeProcess` sets the status to `IDLE` before it clears the process, so a
read that falls between the two gets a null process and throws `NullPointerException`. The executor
catches and logs it, and the next run tries again.

## Contracts

- `fusion-core` interfaces: `TableMaintainer`, `TableMaintainerContext`
  (`getTableConfiguration`, `getMetrics`, `getOptimizingInfo`).
- Table properties are read by the maintainer through `TableConfiguration` (snapshot expiry,
  orphan cleaning, data expiration, tags); see `TableConfigurations` in `../../table`.

## Extending or changing it

New maintenance work belongs in `IcebergTableMaintainer` (`fusion-core`) plus a
`PeriodicTableScheduler` in `../../scheduler/inline`. New code should call
`TableMaintainerFactory`, not the deprecated `TableMaintainers`.

## Invariants and pitfalls

- While a run is in flight, maintenance must not expire the run's target snapshot. The
  `OptimizingInfo` returned here is how the maintainer knows which snapshot to keep.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/optimizing/maintainer/`:
`TestTableMaintainerContext`, `TestSnapshotExpire`, `TestSnapshotExpireIceberg`,
`TestOrphanFileClean`, `TestOrphanFileCleanIceberg`, `TestDataExpire`, `TestDataExpireIceberg`
(all on Iceberg tables).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestTableMaintainerContext -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): `OptimizingProcess`, target snapshot.
- [`../../scheduler/inline`](../../scheduler/inline/README.md): executors that call this package.
- `fusion-core` `io.olake.fusion.maintainer`, `io.olake.fusion.formats.iceberg.maintainer`.
