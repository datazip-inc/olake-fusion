# `io.olake.fusion.optimizing.evaluation`

Metadata-based evaluation: cheap checks, from snapshot summaries and file-size statistics, that can
declare a table or partition "not worth evaluating" without a full plan.

## Role in Fusion

Server side only. Off by default: it is active only when
`self-optimizing.evaluation.fallback-interval` is `>= 0` (`OptimizingConfig.isMetadataBasedTriggerEnabled()`;
default `-1`).

Current callers in `fusion-core` / `fusion-ams` main code:

- `CommonPartitionEvaluator.isNecessary()` calls `isReachFallbackInterval` and
  `isPartitionPendingNecessary` (per-partition gate).
- `CommonPartitionEvaluator.addFile` accumulates the squared error used by that gate when the
  feature is on and `self-optimizing.evaluation.file-size.mse-tolerance > 0`.
- `isEvaluatingNecessary` (table-level gate) has **no caller** in main code, only in
  `TestMetadataBasedEvaluationEvent`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `MetadataBasedEvaluationEvent` | Static checks: `isReachFallbackInterval`, `isEvaluatingNecessary`, `isPartitionPendingNecessary`. | `OptimizingConfig`, `TableStatsProvider` |
| `TableStatsProvider` | Abstract provider of `BasicFileStats` (`dataFileCnt`, `deleteFileCnt`, `totalFileSize`). | |
| `MixedAndIcebergTableStatsProvider` | Singleton (`INSTANCE`) that reads the summary of `table.asUnkeyedTable().currentSnapshot()` (`total-data-files`, `total-delete-files`, `total-files-size`); no snapshot gives zeros. Used by `isEvaluatingNecessary` for `ICEBERG` tables only. | Iceberg `SnapshotSummary` |

## How it works

- `isReachFallbackInterval(config, lastPlanTime)`: `fallbackInterval >= 0 && now - lastPlanTime >= fallbackInterval`.
  When reached, the metadata shortcut is skipped and normal evaluation runs.
- `isEvaluatingNecessary(config, table, lastPlanTime)` (unused):
  1. fallback interval reached → `true`;
  2. no data files in the snapshot summary → `false`;
  3. no delete files and average file size `> targetSize * minTargetSizeRatio` → `false`;
  4. otherwise `true`.
- `isPartitionPendingNecessary(config, squaredErrorSum, fileCount)`: `fileCount > 1 &&
  (mseTolerance == 0 || squaredErrorSum / fileCount >= mseTolerance^2)`. `fileCount` is fragment
  plus undersized-segment files. The squared error per file is
  `(minTargetSize - min(size, minTargetSize))^2`, saturated at `Long.MAX_VALUE`
  (`CommonPartitionEvaluator.updateFileSizeSquaredErrorSum`).

## State, threads and concurrency

Stateless static methods; `MixedAndIcebergTableStatsProvider.INSTANCE` holds no state.
`BasicFileStats` is a mutable per-call accumulator.

## Contracts

- Table properties: `self-optimizing.evaluation.fallback-interval` (ms, default `-1`),
  `self-optimizing.evaluation.file-size.mse-tolerance` (bytes, default `0`), plus
  `self-optimizing.target-size` and `self-optimizing.min-target-size-ratio`.
- Iceberg APIs: `Snapshot.summary()` and `SnapshotSummary.TOTAL_*_PROP` keys (1.7.2).

## Extending or changing it

Add a format-specific provider by extending `TableStatsProvider`; only `ICEBERG` matters in Fusion.
If the table-level gate should be used, it must be wired into the cron path in
`TableRuntimeRefreshExecutor` (fusion-ams); today it is not.

## Invariants and pitfalls

- When enabled, the partition gate can mark a partition unnecessary even though a cron fired, because
  it runs before the Minor/Major/Full checks in `isNecessary()`. It only counts small files, so a
  partition whose only problem is deletes on large files is also skipped while the fallback
  interval has not passed.
- `lastPlanTime` is the latest of the last Minor, Major and Full optimizing times.
- Snapshot summaries can be missing totals (some writers omit them); the provider then sees `0`.

## Tests

`fusion-core/src/test/java/io/olake/fusion/optimizing/evaluation/TestMetadataBasedEvaluationEvent.java`

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-core -am -Dtest=TestMetadataBasedEvaluationEvent -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../plan`](../plan/README.md): `CommonPartitionEvaluator`, the only caller.
- [`..`](../README.md): optimizing types.
