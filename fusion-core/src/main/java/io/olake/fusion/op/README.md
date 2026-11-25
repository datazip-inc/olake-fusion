# `io.olake.fusion.op`

Wrappers around Iceberg table operations and snapshot updates. For Iceberg tables in Fusion they do
two things: run metadata reads and commits as the catalog's user, and (for some operations) maintain
the `watermark.table` property. The compaction commit passes through these classes.

## Role in Fusion

Server side (commit, maintenance), and also present inside the `MixedTable` that the optimizer
deserializes. How Iceberg tables reach these classes:

- `IcebergTable.newIcebergTable` rebuilds the table on `MixedTableOperations` (or
  `MixedHadoopTableOperations` for Hadoop catalogs) through `MixedFormatCatalogUtil.useMixedTableOperations`.
- The table is wrapped in `BasicUnkeyedTable`, whose `newRewrite()`, `newRowDelta()`, `newDelete()`,
  `newAppend()`, `newOverwrite()`, `newReplacePartitions()` and `newTransaction()` return the
  `Mixed*` classes here.
- `UnKeyedTableCommit` (fusion-ams) calls `table.asUnkeyedTable().newTransaction()` → `MixedTransaction`,
  then `transaction.newRewrite()` → `MixedRewriteFiles` and `transaction.newRowDelta()` → `MixedRowDelta`.

`UpdatePartitionProperties` / `PartitionPropertiesUpdate` store per-partition properties in a
table property; nothing in the Iceberg compaction path calls them.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `SnapshotSummary` | Summary keys: `snapshot.producer` (default `INGESTION`), `tx.begin.signature`. | `UnKeyedTableCommit`, maintainers, dashboard |
| `MixedTableOperations` | `TableOperations` delegate that runs `current`, `refresh`, `commit` (and the `temp` operations) inside `AuthenticatedFileIO.doAs`. | `IcebergTable` |
| `MixedHadoopTableOperations` | `HadoopTableOperations` with `doAs` and an in-memory lock; turns a `CommitFailedException` that has a cause into `CommitStateUnknownException` so committed files are not cleaned up. | Hadoop catalogs |
| `MixedUpdate<T>` | Base of all `Mixed*` updates: delegates `set`, `deleteWith`, `stageOnly`, `scanManifestsWith`, `toBranch`, `commit`; optional `WatermarkGenerator`; `Builder` chooses table store / transaction and watermark mode. | `WatermarkGenerator` |
| `MixedTransaction` | `Transaction` wrapper that returns `Mixed*` updates built `inTransaction(transaction)`; `TransactionTable` exposes the transaction's table. | `BasicUnkeyedTable.newTransaction` |
| `MixedRewriteFiles` | `RewriteFiles` wrapper; forwards every method including `validateFromSnapshot`, `dataSequenceNumber`, `deleteFile`, `addFile`. No watermark. | `UnKeyedTableCommit`, maintainers |
| `MixedRowDelta` | `RowDelta` wrapper; builder enables watermark generation. | `UnKeyedTableCommit.addDeleteFiles` |
| `MixedDeleteFiles` | `DeleteFiles` wrapper; `deleteFile(CharSequence)` throws `UnsupportedOperationException`. | `IcebergTableMaintainer.expireFiles` |
| `MixedAppendFiles`, `MixedOverwriteFiles`, `MixedReplacePartitions` | Wrappers with watermark generation; not used by compaction. | Writers |
| `UpdatePartitionProperties`, `PartitionPropertiesUpdate` | Per-partition property map encoded in a table property. | `BasicUnkeyedTable.updatePartitionProperties` |

## How it works

`MixedUpdate.Builder.build()`:

- watermark off (`MixedRewriteFiles`, `MixedDeleteFiles`): wraps `transaction.newX()` when built
  `inTransaction`, else `tableStore.newX()`;
- watermark on (`MixedRowDelta`, `MixedAppendFiles`, `MixedOverwriteFiles`, `MixedReplacePartitions`):
  wraps `transaction.newX()` and, on `commit()`, after the delegate commits, sets
  `watermark.table` in the same transaction if the new watermark is larger; when the builder
  created its own transaction it also calls `commitTransaction()`.

`WatermarkGenerator.forTable` reads `table.event-time-field` (default `_ingest_time`, a virtual field
that no data file has bounds for). With the default, the watermark stays `-1` and nothing is written.

The compaction commit therefore is: `MixedTransaction` → one `MixedRewriteFiles` (and possibly one
`MixedRowDelta`) → `commitTransaction()` → `MixedTableOperations.commit` inside `doAs`.

### The compaction commit through these wrappers (`UnKeyedTableCommit`, fusion-ams)

1. Only tasks with status `SUCCESS` are committed. Delete files listed in `rewrittenDeleteFiles` of
   any non-successful task are excluded from removal (`getExcludedDeleteFiles`).
2. Collected sets: added data files and added delete files (task outputs), removed data files
   (`rewrittenDataFiles`), removed delete files (`rewrittenDeleteFiles` minus the excluded ones).
3. `Transaction transaction = table.asUnkeyedTable().newTransaction()` (a `MixedTransaction`).
4. If no delete file is removed but some are added, it commits in two steps inside the
   transaction, because Iceberg's `BaseRewriteFiles.validateReplacedAndAddedFiles` rejects added
   delete files without replaced ones: a `RewriteFiles` for the data files, then a `RowDelta` with
   `addDeletes`. Otherwise one `RewriteFiles` with all four sets.
5. Each `RewriteFiles` uses `scanManifestsWith(IcebergThreadPools.getCommitExecutor())`, sets
   `snapshot.producer = OPTIMIZE` and, when the target snapshot is valid,
   `validateFromSnapshot(targetSnapshotId)` and `dataSequenceNumber(snapshot(targetSnapshotId).sequenceNumber())`.
6. `transaction.commitTransaction()`. Any exception becomes `OptimizingCommitException`; the
   process is then marked `FAILED` by `TableOptimizingProcess.commit()`.

What Iceberg 1.7.2 validates for that `RewriteFiles` (`BaseRewriteFiles.validate`):

- the replaced files still exist (otherwise `ValidationException`, for example when another
  rewrite or a data expiry removed them since planning);
- no new delete files apply to the replaced data files since `targetSnapshotId`
  (`validateNoNewDeletesForDataFiles`). Because `dataSequenceNumber` is set, equality deletes are
  **ignored** by this check: they keep applying to the new files, which carry the old sequence
  number. A concurrent **position** delete on a replaced file fails the commit; the rewritten rows
  are then not committed and the old files stay, so no data is lost, but the process is wasted.

The `RowDelta` step has no `validateFromSnapshot`, so it is not checked against concurrent changes.

## State, threads and concurrency

Each update object is single-use and not thread-safe (same as Iceberg's). Commit retries and
conflict detection are Iceberg's: on `CommitFailedException` the transaction is retried against
the refreshed metadata per the `commit.retry.*` table properties, and the validations are re-run.
Fusion adds no retry of its own around the compaction commit.

## Contracts

- All methods of the Iceberg 1.7.2 interfaces that the commit uses must be forwarded. Iceberg
  `RewriteFiles` has `default` methods that throw `UnsupportedOperationException`
  (`deleteFile`, `addFile`, `dataSequenceNumber`); `MixedRewriteFiles` overrides them all. Check
  this again when upgrading Iceberg: a new interface method left unimplemented would silently use a
  throwing default or drop a validation.
- `snapshot.producer` values come from `CommitMetaProducer` (`OPTIMIZE`, `DATA_EXPIRATION`,
  `CLEAN_DANGLING_DELETE`, `INGESTION`); maintainers and the dashboard read them.
- These classes are part of the serialized `MixedTable` graph in task inputs (operations are held by
  the wrapped `BaseTable`).

## Extending or changing it

- New Iceberg operation needed by the commit: add the forwarding method to the matching `Mixed*`
  class; do not call the raw Iceberg table from `fusion-ams`, or the commit loses `doAs`.
- Do not turn on watermark generation for `MixedRewriteFiles`: compaction does not add new data, and
  rewritten files would move the watermark.

## Invariants and pitfalls

- `MixedHadoopTableOperations` deliberately reports uncertain Hadoop renames as
  `CommitStateUnknownException`; this prevents Iceberg from deleting files of a commit that may have
  succeeded, at the cost of not retrying.
- `MixedRowDelta` in `UnKeyedTableCommit.addDeleteFiles` is used without `validateFromSnapshot`, so
  it does not check that the referenced data files still exist.
- `MixedDeleteFiles.deleteFile(CharSequence)` is unsupported; callers must pass `DataFile` objects.

## Tests

- `fusion-core/src/test/java/io/olake/fusion/op/TestUpdatePartitionProperties.java`
- Commit path: `fusion-ams/src/test/java/io/olake/fusion/server/optimizing/commit/TestUnKeyedTableCommit.java`

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestUnKeyedTableCommit -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../formats/iceberg`](../formats/iceberg/README.md): builds the wrapped table.
- [`../optimizing`](../optimizing/README.md): task outputs that the commit applies.
- [`../formats/iceberg/maintainer`](../formats/iceberg/maintainer/README.md): other users of these wrappers.
