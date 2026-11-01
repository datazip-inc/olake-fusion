# `io.olake.fusion.maintainer`

Format-neutral API for table maintenance (orphan file cleaning, dangling delete cleaning, snapshot
expiry, data expiry, tag creation) and the context a maintainer gets from the server.

## Role in Fusion

Server side. The interfaces live in `fusion-core` so that maintainers do not depend on
`fusion-ams`. The implementation for Iceberg is
[`IcebergTableMaintainer`](../formats/iceberg/maintainer/README.md); the context implementation is
`DefaultTableMaintainerContext` in `fusion-ams` (`server/optimizing/maintainer`). Maintainers are
created by `TableMaintainers.create` / `TableMaintainerFactory.create` and called from the inline
executors `OrphanFilesCleaningExecutor`, `DanglingDeleteFilesCleaningExecutor`,
`SnapshotsExpiringExecutor`, `DataExpiringExecutor` and `TagsAutoCreatingExecutor`.

These jobs are not compaction, but they share the table with it: snapshot expiry must not expire
the snapshot a running optimizing process was planned on.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TableMaintainer` | `cleanOrphanFiles()`, `cleanDanglingDeleteFiles()` (default no-op), `expireSnapshots()`, `expireData()`, `autoCreateTags()`. | `IcebergTableMaintainer` |
| `TableMaintainerContext` | Gives the maintainer `getTableConfiguration()`, `getMetrics()`, `getOptimizingInfo()`. | `DefaultTableMaintainerContext` |
| `OptimizingInfo` | `isProcessing()` and `getTargetSnapshotId()` of the running optimizing process; `EMPTY` returns `false` / `Long.MAX_VALUE`. | `IcebergTableMaintainer.fetchOptimizingPlanSnapshotTime` |
| `MaintainerMetrics` | `recordOrphanDataFilesCleaned`, `recordOrphanMetadataFilesCleaned`; `NOOP` instance. | `TableOrphanFilesCleaningMetrics` (fusion-ams) |

## How it works

The server builds a context per call. `DefaultTableMaintainerContext.getOptimizingInfo()` returns a
`DefaultOptimizingInfo`, which reports `isProcessing()` from
`tableRuntime.getOptimizingStatus().isProcessing()` and the target snapshot from
`tableRuntime.getOptimizingProcess().getTargetSnapshotId()` (`Long.MAX_VALUE` when not processing). The maintainer reads all switches
and durations from `TableConfiguration` (parsed in `TableConfigurations` from table properties such
as `clean-orphan-file.enabled`, `table-expire.enabled`, `snapshot.keep.duration`); see the Iceberg
maintainer README for the full list and defaults.

## State, threads and concurrency

Interfaces only. Contexts are created per execution; maintainers run on the inline scheduler
threads in `fusion-ams`, concurrently with optimizing on the same table. The only coordination is
`OptimizingInfo` (no lock).

## Contracts

- `OptimizingInfo.getTargetSnapshotId()` must return `Long.MAX_VALUE` when nothing runs; callers treat
  it as "no constraint".
- Adding a method to `TableMaintainer` affects every implementation; prefer a `default` method.

## Extending or changing it

Add maintenance behaviour in `IcebergTableMaintainer`; extend the interface only for a new job type
and wire a new inline executor in `fusion-ams`.

## Invariants and pitfalls

- A maintainer must never remove files or snapshots that a running optimizing process needs to
  commit; `OptimizingInfo` is how it knows.
- `MaintainerMetrics.NOOP` and `OptimizingInfo.EMPTY` are for tests or contexts without a runtime;
  using `EMPTY` in production removes the snapshot-expiry guard.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/optimizing/maintainer/TestTableMaintainerContext.java`
and the Iceberg maintainer tests in the same directory.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestTableMaintainerContext -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../formats/iceberg/maintainer`](../formats/iceberg/maintainer/README.md): implementation.
- [`../optimizing`](../optimizing/README.md): the process whose snapshot is protected.
