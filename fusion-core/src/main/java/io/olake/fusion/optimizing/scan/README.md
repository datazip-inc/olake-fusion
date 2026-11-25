# `io.olake.fusion.optimizing.scan`

File scan used by the evaluator and planner: lists every live data file of a snapshot together with
the delete files that apply to it.

## Role in Fusion

Server side only, at the very start of planning. `AbstractOptimizingEvaluator.initEvaluator`
always creates an `IcebergTableFileScanHelper` over `mixedTable.asUnkeyedTable()` and the
evaluated snapshot id, sets the partition filter, and iterates `scan()`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TableFileScanHelper` | Interface: `scan()`, `withPartitionFilter(Expression)`, `getSpec(int)`; nested `FileScanResult(DataFile file, List<ContentFile<?>> deleteFiles)`. | `AbstractOptimizingEvaluator` |
| `IcebergTableFileScanHelper` | Iceberg implementation over `Table.newScan()`. | Iceberg `TableScan`, `IcebergThreadPools` |

## How it works

`IcebergTableFileScanHelper.scan()`:

1. If the snapshot id is `Constants.INVALID_SNAPSHOT_ID` (empty table), returns
   `CloseableIterable.empty()`.
2. Otherwise runs
   `table.newScan().planWith(IcebergThreadPools.getPlanningExecutor()).useSnapshot(snapshotId).filter(partitionFilter).planFiles()`
   and maps each `FileScanTask` to `FileScanResult(task.file(), new ArrayList<>(task.deletes()))`.
3. `getSpec(specId)` uses `table.specs()` captured in the constructor.

Iceberg decides which deletes are in `task.deletes()`: position deletes and equality deletes of the
same partition whose sequence number is higher than the data file's (equality: strictly higher;
position: higher or equal), plus global equality deletes.

## State, threads and concurrency

The helper holds the table, snapshot id, filter and spec map; one instance per evaluation. Manifest
reading is parallel on the shared planning executor. The returned `CloseableIterable` must be closed
by the caller (the evaluator uses try-with-resources).

## Contracts

- Results reflect exactly one snapshot (`useSnapshot`), the one later used as the commit's
  `targetSnapshotId`.
- The filter is a row filter. Iceberg prunes by partition **and** by column metrics, so a filter
  on a non-partition column can skip data files inside a partition (see the planner pitfalls).
- Iceberg APIs (1.7.2): `TableScan.planWith`, `useSnapshot`, `filter`, `planFiles`,
  `FileScanTask.file()` / `deletes()`.

## Extending or changing it

Keep `scan()` returning one entry per data file with **all** its deletes; the planner's
`reservedDeleteFiles` logic depends on seeing every delete of every data file.
No splitting (`planTasks()`) must be used here: split tasks would repeat a data file.

## Invariants and pitfalls

- Do not change `planFiles()` to an incremental or filtered-by-time scan: missing a data file means
  its deletes are not reserved and may be removed at commit.
- The delete list is copied into a new `ArrayList`; evaluators may keep it.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/optimizing/scan/TestIcebergTableFileScanHelper.java`
(base `TableFileScanHelperTestBase`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestIcebergTableFileScanHelper -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../plan`](../plan/README.md): consumer.
- [`../../scan`](../../scan/README.md): `TableEntriesScan` (manifest-entry scan used by maintenance).
