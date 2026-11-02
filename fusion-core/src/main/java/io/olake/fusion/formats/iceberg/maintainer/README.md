# `io.olake.fusion.formats.iceberg.maintainer`

Iceberg implementation of `TableMaintainer`: orphan file cleaning, dangling delete file cleaning,
snapshot expiry, data expiry and automatic tags.

## Role in Fusion

Server side, run by the inline executors in `fusion-ams/.../scheduler/inline/`
(`OrphanFilesCleaningExecutor`, `DanglingDeleteFilesCleaningExecutor`, `SnapshotsExpiringExecutor`,
`DataExpiringExecutor`, `TagsAutoCreatingExecutor`) through `TableMaintainers.create`. Not part of
compaction, but it commits to the same tables and deletes files, so it must respect running
optimizing processes.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `IcebergTableMaintainer` | All five jobs for an Iceberg `Table`. | `TableMaintainerContext`, `IcebergTableUtil`, `RollingFileCleaner`, `TableEntriesScan` |
| `AutoCreateIcebergTagAction` | Creates the tag for the current period if missing. | `TagConfiguration`, `ManageSnapshots` |

## How it works

Properties (parsed in `TableConfigurations`, fusion-ams) and defaults:

| Job | Switch (default) | Other properties |
| --- | --- | --- |
| Orphan files | `clean-orphan-file.enabled` (`false`) | `clean-orphan-file.min-existing-time-minutes` (`2880`) |
| Dangling deletes | `clean-dangling-delete-files.enabled` (`true`) | |
| Snapshot expiry | `table-expire.enabled` (`false`) | `snapshot.keep.duration` (`720min`), `snapshot.keep.min-count` (`1`), `snapshot.keep.flink.checkpoint-retention` (`7d`) |
| Data expiry | `data-expire.enabled` (`false`) and Iceberg `gc.enabled` (`true`) | `data-expire.field`, `data-expire.level` (`partition`), `data-expire.retention-time`, `data-expire.base-on-rule` (`LAST_COMMIT_TIME`), `data-expire.datetime-string-pattern` (`yyyy-MM-dd`), `data-expire.datetime-number-format` (`TIMESTAMP_MS`) |
| Tags | `tag.auto-create.enabled` (`false`) | `tag.auto-create.trigger.period` (`daily`), `tag.auto-create.trigger.offset.minutes` (`0`), `tag.auto-create.trigger.max-delay.minutes` (`60`), `tag.auto-create.max-age-ms` (`-1`), `tag.auto-create.tag-format` |

### `cleanOrphanFiles()`

1. `keepTime = orphanExistingMinutes * 60_000`; cutoff = `now - keepTime`.
2. `cleanContentFiles(cutoff, metrics)`: valid set = all content file paths of **all** snapshots
   (`IcebergTableUtil.getAllContentFilePath`, via `ALL_ENTRIES`) plus statistics files. Under
   `<location>/data` it lists files with prefix listing (`listPrefix`) and deletes those not in the
   valid set and older than the cutoff. Directories are not deleted, so empty ones stay (on object
   stores such as S3 they do not exist on their own). If the FileIO has no prefix listing, it logs a
   warning and skips.
3. `table.refresh()`, then `cleanMetadata(cutoff, metrics)`: valid set = manifest lists, all
   manifests (`ALL_MANIFESTS`), metadata files, statistics files, version hint. Files under
   `<location>/metadata` not in the set and older than the cutoff are deleted, except names matching
   the latest `flink.job-id` prefix. Needs prefix listing; otherwise it logs a warning and skips.

### `cleanDanglingDeleteFiles()`

If the current snapshot summary has `total-delete-files > 0`, `IcebergTableUtil.getDanglingDeleteFiles`
returns delete files present in the snapshot's entries but referenced by no `FileScanTask`. They are
removed with `table.newRewrite().rewriteFiles(∅, dangling, ∅, ∅)`, `snapshot.producer =
CLEAN_DANGLING_DELETE`. A `ValidationException` is logged and ignored.

### `expireSnapshots()`

`mustOlderThan(now)` = min of `now - snapshot.keep.duration`, the timestamp of the running optimizing
process's target snapshot (`fetchOptimizingPlanSnapshotTime`, via `OptimizingInfo`), and the
timestamp of the latest snapshot not produced by Fusion maintenance
(`fetchLatestNonOptimizedSnapshotTime`, producers `OPTIMIZE`, `DATA_EXPIRATION`,
`CLEAN_DANGLING_DELETE`). If a Flink checkpoint snapshot is younger than the Flink retention, it is
kept too. Then Iceberg `expireSnapshots().retainLast(max(minCount, 1)).expireOlderThan(t)
.deleteWith(cleaner::addFile).cleanExpiredFiles(true).commit()`; `RollingFileCleaner` deletes the
collected files in groups of 1000 and the rest in `clear()`; it does not delete directories.

### `expireData()`

Validates the field (types `TIMESTAMP`, `STRING`, `LONG`, `DATE`), computes the base time
(`CURRENT_TIME` or last non-optimized commit), subtracts the retention, scans files with column
stats, and expires:

- level `FILE`: data files whose upper bound is at or before the cutoff, and delete files with
  `dataSequenceNumber <= ` the latest expired data file's sequence in that partition;
- level `PARTITION`: all files of a partition only if **all** its data files are expired.

Data files go through `table.newDelete()`; delete files through
`newRewrite().validateFromSnapshot(snapshotId)`, both with `snapshot.producer = DATA_EXPIRATION`.

### `autoCreateTags()`

`AutoCreateIcebergTagAction.execute()`: computes the tag name for the period, returns if the tag
exists, finds the first snapshot with `timestampMillis >= triggerTime`, skips it if later than
`max-delay` minutes, else `manageSnapshots().createTag(...)` (with `setMaxRefAgeMs` when
`max-age-ms > 0`).

## State, threads and concurrency

One maintainer per job run; the `table` field is public and mutable. Runs concurrently with
optimizing on the same table: Iceberg optimistic concurrency handles commit races, and
`OptimizingInfo` protects the optimizing target snapshot from expiry. `expiredFileScan` uses parallel
streams and concurrent maps.

## Contracts

- `snapshot.producer` values (`CommitMetaProducer.*`) are read by `fetchLatestNonOptimizedSnapshotTime`
  and by `IcebergTableUtil.findLatestOptimizingSnapshot`; keep them stable.
- Iceberg APIs (1.7.2): `ExpireSnapshots`, `RewriteFiles`, `DeleteFiles`, `ReachableFileUtil`,
  `MetadataTableType.ALL_ENTRIES` / `ALL_MANIFESTS`, `SupportsPrefixOperations`,
  `ManageSnapshots`.

## Extending or changing it

Override the protected hooks (`expireSnapshotNeedToExcludeFiles`, `orphanFileCleanNeedToExcludeFiles`,
`mustOlderThan`) rather than the public jobs. Keep orphan cleaning based on **all** snapshots' files.

## Invariants and pitfalls

- **Orphan cleaning deletes data.** It deletes every file under `data/` older than the cutoff that
  no snapshot references, including files written by an in-flight optimizer task or a writer that
  has not committed yet. The default 2 days must stay longer than any task or ingestion commit can
  take.
- Files in the table location that belong to another table (shared location) would be seen as
  orphans.
- Snapshot expiry must keep the optimizing target snapshot: `UnKeyedTableCommit` reads
  `snapshot(targetSnapshotId).sequenceNumber()` and would fail if it were gone.
- Dangling delete cleaning relies on `planFiles()` of the current snapshot; position deletes on data
  files that still exist are never dangling.
- `expireData` catches and logs every `Throwable`; failures are silent apart from the log.
- Close every `CloseableIterable` (done with try-with-resources in `expiredFileScan` and the utils).

## Tests

- `fusion-core/src/test/java/io/olake/fusion/formats/iceberg/maintainer/TestAutoCreateIcebergTagAction.java`
- `fusion-ams/src/test/java/io/olake/fusion/server/optimizing/maintainer/`: `TestOrphanFileCleanIceberg`,
  `TestSnapshotExpireIceberg`, `TestDataExpireIceberg`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestSnapshotExpireIceberg -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../../../maintainer`](../../../maintainer/README.md): API and context.
- [`../utils`](../utils/README.md): file listing helpers and `RollingFileCleaner`.
- [`../../../scan`](../../../scan/README.md): `TableEntriesScan`.
