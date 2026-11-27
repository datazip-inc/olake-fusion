# `io.olake.fusion.server.dashboard.model`

Request and response beans of the REST API. Jackson serialises them by their getters, so each
getter name is a JSON field that Fusion's web UI or OLake-UI may read.

## Role in Fusion

- Built by the controllers in [`controller`](../controller/README.md), by `OverviewManager`,
  `MixedAndIcebergTableDescriptor`, `TableStatCollector`, `OptimizingUtil`, `TerminalManager` and
  `DefaultTableManager`.
- Request bodies parsed with `ctx.bodyAsClass(...)`: `CatalogRegisterInfo`.
- Table detail, snapshot and optimizing-process responses use classes in `fusion-core`
  (`io.olake.fusion.table.descriptor`: `ServerTableMeta`, `TableSummary`, `OptimizingProcessInfo`,
  ...), not this package.

## Key classes

| Class | Responsibility | Used by |
| --- | --- | --- |
| `CatalogRegisterInfo` | Body of `POST`/`PUT /catalogs`: `name`, `type`, `optimizerGroup`, `tableFormatList`, `storageConfig`, `authConfig`, `properties`, `tableProperties` (all string maps) | `CatalogController` (OLake-UI builds it in `mapper.go`) |
| `CatalogSettingInfo` | Response of `GET /catalogs/{name}`: same fields, but `storageConfig`/`authConfig` are `Map<String,Object>` that may hold `ConfigFileItem {fileName, fileUrl}` for uploaded files | `CatalogController` |
| `ApiTokens` | `id`, `apikey`, `secret`, `applyTime`; row of `api_tokens` | `ApiTokenController`, `APITokenManager`, `ApiTokensMapper` |
| `PlatformFileInfo` | `fileId`, `fileName`, `fileContent` (base64), `filePath` | `PlatformFileManager`, `PlatformFileMapper` |
| `SessionInfo` | `{sessionId}`, answer of terminal execute | `TerminalController` |
| `LogInfo` | `{logStatus, logs}`, answer of terminal logs; `logStatus` defaults to `SqlStatus.CREATED.getName()` | `TerminalManager` |
| `SqlResult` | `{id, status, columns, rowData}` per statement | `TerminalManager` |
| `LatestSessionInfo` | `{sessionId, sql}` of the last terminal run | `TerminalManager` |
| `SqlExample` | Enum of example SQL snippets (`CreateTable`, `DeleteTable`, ...) | `TerminalController` |
| `SqlStatus` | Enum `CREATED`..`CANCELED` with display names | `LogInfo`, `SqlResult` |
| `TableMeta` | `{name, type}` row of the table list; `TableType` enum with the single value `ICEBERG` | `TableController.getTableList` (OLake-UI) |
| `TableOperation` | `{ts, operation}`; `buildFromDDLInfo` | `TableController.getTableOperations` |
| `TableBasicInfo`, `TableStatistics` | Internal statistics holders for table detail | `MixedAndIcebergTableDescriptor`, `TableStatCollector` |
| `TableOptimizingInfo` | Row of `GET /optimize/optimizerGroups/{group}/tables`: `tableName`, `optimizeStatus`, `duration`, `fileCount`, `fileSize`, `quota`, `quotaOccupation`, `groupName` | `OptimizingUtil`, `DefaultTableManager`, `OptimizerGroupController` |
| `OptimizerInstanceInfo` | Row of the optimizer list (`token`, `startTime`, `touchTime`, `jobId`, `groupName`, `coreNumber`, `memory`, `jobStatus`, `container`); has a `Builder` | `OptimizerGroupController.getOptimizers` |
| `OptimizerResourceInfo` | `resourceGroup`, `occupationCore`, `occupationMemory` | `OptimizerGroupController` |
| `OverviewSummary`, `OverviewTopTableItem`, `OverviewDataSizeItem`, `OverviewResourceUsageItem` | Overview page data | `OverviewManager`, `OverviewController` |
| `VersionInfo` | `{version, commitTime}` | `VersionController` |
| `SnapshotInfo` | Snapshot counters | only `TableStatCollector.buildBaseTableSnapshotInfo`, which nothing calls |

## How it works

These are plain beans with getters and setters (and a few constructors). There is no validation
here; controllers validate.

## State, threads and concurrency

Instances are created per request or per overview refresh. No shared state.

## Contracts

- JSON field names come from getter names. `TableMeta` has public fields `name`, `type`.
- OLake-UI depends on: `ApiTokens.apikey` and `.secret`, `SessionInfo.sessionId`,
  `LogInfo.logStatus` and `.logs`, `TableMeta.name` and `.type` (the table list), and the
  `CatalogRegisterInfo` field names it posts. Do not rename these.
- `TableMeta.type` for Iceberg tables is `ICEBERG` (`TableController.getTableList` maps
  `TableFormat.ICEBERG` to `TableMeta.TableType.ICEBERG.toString()`).

## Extending or changing it

Add a bean here when a new endpoint needs a response shape that is not already in
`io.olake.fusion.table.descriptor`. Give it a no-arg constructor if it is a request body.

## Invariants and pitfalls

- `SnapshotInfo` is only built by `TableStatCollector.buildBaseTableSnapshotInfo`, which nothing
  calls.

## Tests

No tests target this package directly. Model fields are exercised through controller and manager
tests, for example:
`./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestOverviewManager -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`dashboard/controller`](../controller/README.md), [`dashboard`](../README.md),
  [`dashboard/utils`](../utils/README.md), [`terminal`](../../terminal/README.md)
