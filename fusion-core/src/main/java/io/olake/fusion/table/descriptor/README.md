# `io.olake.fusion.table.descriptor`

The response types of the table pages of Fusion's REST API (`/api/ams/v1/tables/...`) and the `FormatTableDescriptor` interface that fills them.

## Role in Fusion

`fusion-ams` `io.olake.fusion.server.dashboard.MixedAndIcebergTableDescriptor` implements `FormatTableDescriptor` for Iceberg tables; `TableController` calls it and returns the objects of this package as JSON (`ctx.json(OkResponse.of(...))`, Jackson via `JavalinJsonMapper.createDefaultJsonMapper()`). OLake-UI's Go backend and frontend read these shapes, either through its own handlers or through the `/api/opt/v1/...` → `/api/ams/v1/...` proxy.

Runs in: **server** only.

Mixed-format only (out of scope): `ServerTableMeta.pkList`, `changeMetrics`, `baseWatermark`/`tableWatermark` for keyed tables, `ConsumerInfo` (`getTableConsumerInfos`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `FormatTableDescriptor` | One method per table page: `getTableDetail`, `getSnapshots`, `getSnapshotDetail`, `getTableOperations`, `getTablePartitions`, `getTableFiles`, `getOptimizingProcessesInfo`, `getTableOptimizingTypes`, `getOptimizingTaskInfos`, `getTableTags`, `getTableBranches`, `getTableConsumerInfos`; plus `withIoExecutor`, `supportFormat`. | `FusionTable`, `ProcessStatus` |
| `ServerTableMeta` | `GET .../tables/{table}/details`: `tableType`, `tableIdentifier`, `schema` (`AMSColumnInfo`), `pkList`, `partitionColumnList` (`AMSPartitionField`), `properties`, `changeMetrics`, `baseMetrics`, `tableSummary`, `baseLocation`, `filter`, `createTime`, `creator`, `tableWatermark`, `baseWatermark`, `comment`. | `TableSummary` |
| `TableSummary` (this package) | Detail-page summary: `file`, `size`, `averageFile`, `tableFormat`, `records`, `optimizingStatus`, `healthScore` (-1 = not computed), `smallFileScore`, `equalityDeleteScore`, `positionalDeleteScore`. Not the same class as `io.olake.fusion.table.TableSummary`. | `TableController.getTableDetail` |
| `OptimizingProcessInfo` | One optimizing process (OLake-UI "run"): `tableId`, `catalogName`, `dbName`, `tableName`, `processId` (String), `startTime`, `optimizingType`, `status` (`ProcessStatus`), `failReason`, `duration`, `successTasks`, `totalTasks`, `runningTasks`, `finishTime`, `inputFiles`/`outputFiles` (`FilesStatistics`), `summary`. | `FilesStatistics` |
| `OptimizingTaskInfo` | One task of a process; constructor puts `retry-count`, and when a token exists `optimizer.token` and `optimizer.thread-id`, into `summary`. `costTime` is computed from `startTime` while running. | `ProcessTaskStatus` |
| `FilesStatistics`, `FilesStatisticsBuilder` | `fileCnt`, `totalSize`, `averageSize` (computed in the constructor). | — |
| `FusionSnapshotsOfTable` | One snapshot: `snapshotId` (String), `fileCount`, `fileSize` (getter returns a formatted String), `originalFileSize` (long), `records`, `commitTime`, `operation`, `producer`, `summary`, `recordsSummaryForChart`, `filesSummaryForChart`. | `CommonUtil.byteToXB` |
| `PartitionBaseInfo`, `PartitionFileBaseInfo` | Partition rows and file rows (`fileType`, `commitId`, `commitTime`, `path`, `file`, `fileSize`, `size`, `specId`, `operation`). | `CommonUtil` |
| `TagOrBranchInfo` | Tag/branch rows; `MAIN_BRANCH` constant. | — |
| `AMSColumnInfo`, `AMSPartitionField` | Column (`field`, `type`, `required`, `comment`) and partition field (`field`, `sourceField`, `transform`, `fieldId`, `sourceFieldId`). | — |
| `DDLInfo`, `DDLReverser`, `TableMetaExtract`, `MetadataChangeHandler`, `SparkMetadataChangeHandler` | `GET .../operations`: compares consecutive historical metadata (`TableMetaExtract.extractTable`) and renders the differences as Spark SQL `ALTER TABLE` strings. | — |
| `OperationType` | Snapshot filter `all` / `optimizing` / `non-optimizing` (`OperationType.of`). | — |

## How it works

- **Detail.** `MixedAndIcebergTableDescriptor.getTableDetail` fills `ServerTableMeta`, puts `lastCommitTime`, `totalSize` (formatted string), `fileCount`, `averageFileSize`, `baseWatermark` into `baseMetrics`, and creates a `TableSummary`. `TableController.getTableDetail` then sets `optimizingStatus` and copies `healthScore` and the three sub-scores from the runtime `io.olake.fusion.table.TableSummary`; for a table without a runtime it sets `optimizingStatus = IDLE`.
- **Operations.** `DDLReverser.reverse` returns nothing for 0 or 1 metadata versions; otherwise one `DDLInfo` per property change (`UPDATE_PROPERTIES`) and per schema change (`UPDATE_SCHEMA`), timestamped with the newer version's time.

## State, threads and concurrency

Plain mutable DTOs, created per request. `FormatTableDescriptor.withIoExecutor` hands the implementation a shared IO pool.

## Contracts

Jackson serializes by getters, so **getter names are the JSON field names**. What OLake-UI reads today (`olake-ui/server/internal/services/optimization/table.go` and `ui/src/modules/maintenance/features/tables/`):

| Endpoint | Fields OLake-UI depends on |
| --- | --- |
| `.../tables/{table}/details` (`ServerTableMeta`) | `baseMetrics.totalSize` (must be a string), `baseMetrics.averageFileSize`, `fileCount`, `lastCommitTime`; `properties` (`self-optimizing.enabled`, `olake_2pc`); `tableSummary.healthScore` (number; the Go code errors if missing) |
| `.../optimizing-processes?type=&page=&pageSize=` (`OptimizingProcessInfo` list in `result.list`) | `processId` (string), `status` (enum name as string), `finishTime`, `optimizingType`, `startTime`, `duration`, `summary` |
| `.../snapshots` (`FusionSnapshotsOfTable`) | `commitTime`, `filesSummaryForChart["data-files"]`, `filesSummaryForChart["delete-files"]` |
| `.../optimizing-processes/{processId}/cancel` | (no body) |

Other shapes worth keeping stable: `FusionSnapshotsOfTable.fileSize` is a human-readable string and `originalFileSize` is the byte count; `PartitionBaseInfo` emits both `fileSize` and `size`; `PartitionFileBaseInfo` emits `path` and `file`.

If you change any field, grep OLake-UI for its callers and report what breaks (CLAUDE.md section 3).

## Extending or changing it

- Add fields, do not rename them. A new getter adds a JSON field automatically.
- A new per-table page: add a method to `FormatTableDescriptor`, implement it in `MixedAndIcebergTableDescriptor`, add a route in `DashboardServer` and a handler in `TableController`.

## Invariants and pitfalls

- `OptimizingTaskInfo`'s constructor writes into the `summary` map it is given: passing `null` throws `NullPointerException`, and an immutable map throws `UnsupportedOperationException`.
- Two classes are named `TableSummary` (`io.olake.fusion.table` and this package); `TableController` uses both.
- `SparkMetadataChangeHandler` does not escape quotes in property values or comments; the generated SQL is for display only.

## Tests

`fusion-core/src/test/java/io/olake/fusion/table/descriptor/TestServerTableDescriptor.java` is abstract; the concrete Iceberg test is `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestIcebergServerTableDescriptor.java`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestIcebergServerTableDescriptor -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md) (runtime `TableSummary`)
- [`../../utils`](../../utils/README.md) (`CommonUtil.byteToXB`)
