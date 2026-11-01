# `io.olake.fusion.server.dashboard.controller`

The REST handlers behind Fusion's HTTP API (`/api/ams/v1/...`, port `http-server.bind-port`, 1630 in
`local-test/config.yaml`). Each class groups the handlers for one area: catalogs, tables, optimizer,
terminal, logs, login and API tokens, overview, settings and a few small ones.

## Role in Fusion

- **Callers.**
  - **OLake-UI's Go backend** (`olake-ui/server`). It logs in once with a password, creates an API
    token, and then signs every call with that token (see "Contracts"). It calls some endpoints
    directly (`server/internal/constants/constants.go`) and forwards any other
    `/api/opt/v1/<path>` to `/api/ams/v1/<path>` unchanged (`piggyBacking.go`).
  - **Fusion's own web UI** (`fusion-web`, served from `static/`). It uses the login session and
    sends `X-Request-Source: Web`. It is not the product UI.
  - Not the optimizer: optimizers talk to the server over Thrift, not over this API.
- **Wiring.** `DashboardServer` (parent package) builds every controller in its constructor and
  maps routes to them in `endpoints()` / `apiGroup()`. Controllers are plain classes with
  `public void handler(io.javalin.http.Context ctx)` methods; there is no annotation routing.
- **What they call.** `CatalogManager`, `TableManager`, `OptimizerManager` (server services),
  `ServerTableDescriptor` (table metadata reads), `TerminalManager` (SQL terminal),
  `APITokenManager`, `PlatformFileManager`, `OverviewManager`, `Containers` and the
  `AbstractOptimizerContainer` (to start or stop optimizers), and the server's own Thrift
  `OptimizingService` (to cancel a process).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `CatalogController` | Register, read, update, delete catalogs; list metastore types, table formats and storage types; serve uploaded catalog config files | `CatalogManager`, `PlatformFileManager`, `CatalogRegisterInfo`, `CatalogSettingInfo`, `PropertiesUtil`, `DesensitizationUtil` |
| `TableController` | Table detail, snapshots, partitions, files, DDL history, tags/branches, optimizing processes (Runs) and their tasks, cancel a process; list catalogs, databases and tables | `ServerTableDescriptor`, `TableManager`, `CatalogManager`, `OptimizingClientPools` |
| `OptimizerGroupController` | Optimizer groups (resource groups), optimizers per group, tables per group, scale out | `OptimizerManager`, `TableManager`, `Containers` |
| `OptimizerController` | Create an optimizer from a body (`POST /optimize/optimizers`) and release one by `jobId` | `OptimizerManager`, `Containers`, `AbstractOptimizerContainer` |
| `TerminalController` | SQL terminal: submit a script, read logs and results, stop, examples | `TerminalManager`, `SqlExample` |
| `LogController` | Read Spark compaction logs of one optimizing process from disk, as JSON or as a download | Local file system under `LOG_DIR` |
| `LoginController` | Password login, current user, logout | `PasswdAuthenticationProvider` (via `HttpAuthenticationFactory`) |
| `ApiTokenController` | Create, list, delete API tokens; compute and **check** request signatures | `APITokenManager`, `ParamSignatureCalculator` |
| `OverviewController` | Overview summary, history and top tables, from the cache in `OverviewManager` | `OverviewManager` |
| `SettingController` | Server config (passwords masked) and container settings | `Configurations`, `Containers`, `OptimizerManager` |
| `PlatformFileInfoController` | Upload a file (stored base64 in the database) and download it | `PlatformFileManager` |
| `HealthCheckController` | HA state of this server | `FusionServiceContainer.getHaState()` |
| `VersionController` | Version and commit time from `amoro/git.properties` | classpath resource |
| `TelemetryController` | Store the telemetry install id | `PlatformPropertyStore`, `Telemetry` |

## How it works

### Routes

All paths are under `/api/ams/v1`. "OLake-UI" marks routes OLake-UI's backend calls itself
(constants in `constants.go` or `client.go`) and routes its frontend reaches through the forwarder
(`ui/src/config/apiConfig.ts`, `tableService.ts`, `catalogService.ts`). Every handler answers with
`OkResponse` unless noted.

**Login and tokens**

| Method + path | Handler | Returns | OLake-UI |
| --- | --- | --- | --- |
| `GET /login/current` | `LoginController.getCurrent` | session `SessionInfo {userName, loginTime}` or `null` | |
| `POST /login` | `LoginController.login` | `"success"`; sets session attribute `user`. Body `{"user","password"}` | yes (`client.go`) |
| `POST /logout` | `LoginController.logout` | `OkResponse.ok()`; removes the `JSESSIONID` cookie | |
| `POST /api/token/create` | `ApiTokenController.createApiToken` | `ApiTokens {id, apikey, secret, applyTime}`, secret in clear text once | yes (`client.go`) |
| `POST /api/token/delete?apiKey=` | `ApiTokenController.deleteApiToken` | message | |
| `GET /api/token/info` | `ApiTokenController.getApiTokens` | list of `ApiTokens`, secret masked as `******` | |
| `POST /api/token/calculate/signature?apiKey=&secret=&...` | `calculateSignature` | the signature for these params (helper for clients) | |
| `POST /api/token/calculate/encryptString?apiKey=&...` | `getEncryptStringFromQueryParam` | the string that is signed | |

**Catalogs** (`CatalogController`, plus three in `TableController`)

| Method + path | Handler | Returns | OLake-UI |
| --- | --- | --- | --- |
| `GET /catalogs` | `TableController.getCatalogs` | list of Thrift `CatalogMeta` (all properties) | yes |
| `POST /catalogs` | `createCatalog` | `""`. Body `CatalogRegisterInfo` | yes |
| `GET /catalogs/{catalogName}` | `getCatalogDetail` | `CatalogSettingInfo`, or `null` if absent | yes |
| `PUT /catalogs/{catalogName}` | `updateCatalog` | `ok()`. Body `CatalogRegisterInfo` (the name comes from the body) | yes |
| `DELETE /catalogs/{catalogName}` | `deleteCatalog` | `"OK"` | yes |
| `GET /catalogs/{catalogName}/delete/check` | `catalogDeleteCheck` | `true`, or for an internal catalog whether it has no tables | |
| `GET /catalogs/{catalogName}/config/{type}/{key}` | `getCatalogConfFileContent` | raw bytes of a stored `storage-config` or `auth-config` file | |
| `GET /catalogs/metastore/types` | `getCatalogTypeList` | `[{value, display}]` | |
| `GET /catalogs/metastore/{type}/table-formats` | `getMetastoreTableFormats` | format names allowed for this type (always `["ICEBERG"]` for a listed type) | |
| `GET /catalogs/metastore/{type}/storage-types` | `getMetastoreStorageTypes` | storage types allowed for this type | |
| `GET /catalogs/{catalog}/databases?keywords=` | `TableController.getDatabaseList` | list of names | yes (forwarded) |
| `GET /catalogs/{catalog}/databases/{db}/tables?keywords=` | `TableController.getTableList` | list of `TableMeta {name, type}` | yes |

**Tables** (`TableController`, prefix `/tables/catalogs/{catalog}/dbs/{db}/tables/{table}`).
List endpoints take `page` (default 1) and `pageSize` (default 20) and return `PageResult`.

| Method + suffix | Handler | Returns | OLake-UI |
| --- | --- | --- | --- |
| `GET /details` | `getTableDetail` | `ServerTableMeta`; its `tableSummary` gets `optimizingStatus`, `healthScore`, `smallFileScore`, `equalityDeleteScore`, `positionalDeleteScore` from the table runtime | yes |
| `GET /optimizing-processes?type=&status=` | `getOptimizingProcesses` | `PageResult<OptimizingProcessInfo>` (Runs). `type` is `MINOR`/`MAJOR`/`FULL` (Lite/Medium/Full), `status` a `ProcessStatus` name | yes |
| `GET /optimizing-types` | `getOptimizingTypes` | map `OptimizingType` name to display value | |
| `GET /optimizing-processes/{processId}/tasks` | `getOptimizingProcessTasks` | `PageResult<OptimizingTaskInfo>` | |
| `POST /optimizing-processes/{processId}/cancel` | `cancelOptimizingProcess` | `ok()`; calls Thrift `OptimizingService.cancelProcess` | yes (forwarded) |
| `GET /snapshots?ref=&operation=` | `getTableSnapshots` | `PageResult<FusionSnapshotsOfTable>`; `operation` is an `OperationType` (default all) | yes (forwarded, `pageSize=1`) |
| `GET /snapshots/{snapshotId}/detail?ref=` | `getSnapshotDetail` | `PageResult<PartitionFileBaseInfo>` | |
| `GET /partitions?filter=` | `getTablePartitions` | `PageResult<PartitionBaseInfo>`, sorted by partition descending | |
| `GET /partitions/{partition}/files?specId=` | `getPartitionFileListInfo` | `PageResult<PartitionFileBaseInfo>` | |
| `GET /operations` | `getTableOperations` | `PageResult<TableOperation>` (DDL history, newest first) | |
| `GET /tags`, `GET /branches` | `getTableTags`, `getTableBranches` | `PageResult<TagOrBranchInfo>` (main branch first) | |
| `GET /consumers` | `getTableConsumerInfos` | always empty for Iceberg | |

**Optimizer** (`/optimize`; Fusion has one group, `spark-container` in deployments, `local` for debugging)

| Method + path | Handler | Returns |
| --- | --- | --- |
| `GET /optimize/actions` | `getActions` | display values of every `OptimizingStatus` |
| `GET /optimize/optimizerGroups` | `getOptimizerGroups` | `[{optimizerGroupName}]` |
| `GET /optimize/optimizerGroups/{optimizerGroup}/tables?dbSearchInput=&tableSearchInput=&actions[]=` | `getOptimizerTables` | `PageResult<TableOptimizingInfo>`; group `all` means no filter |
| `GET /optimize/optimizerGroups/{optimizerGroup}/optimizers` | `getOptimizers` | `PageResult<OptimizerInstanceInfo>`, newest first, `jobStatus` always `"RUNNING"` |
| `GET /optimize/optimizerGroups/{optimizerGroup}/info` | `getOptimizerGroupInfo` | `OptimizerResourceInfo` (cores, memory) |
| `POST /optimize/optimizerGroups/{optimizerGroup}/optimizers` | `scaleOutOptimizer` | starts an optimizer; body `{"parallelism":N}` |
| `POST /optimize/optimizers` | `OptimizerController.createOptimizer` | same, body `{"parallelism","optimizerGroup"}` |
| `DELETE /optimize/optimizers/{jobId}` | `OptimizerController.releaseOptimizer` | releases the resource in its container and deletes resource + optimizer rows |
| `GET/POST/PUT /optimize/resourceGroups`, `DELETE /optimize/resourceGroups/{resourceGroupName}`, `GET .../{resourceGroupName}/delete/check` | `getResourceGroup`, `createResourceGroup`, `updateResourceGroup`, `deleteResourceGroup`, `deleteCheckResourceGroup` | group CRUD. Body `{"name","container","properties"}`; name must match `^[A-Za-z0-9_-]{1,50}$` |
| `GET /optimize/containers/get` | `getContainers` | container names |

**Terminal** (`TerminalController`)

| Method + path | Handler | Returns | OLake-UI |
| --- | --- | --- | --- |
| `POST /terminal/catalogs/{catalog}/execute` | `executeScript` | `SessionInfo {sessionId}`. Body `{"sql": "..."}` | yes (table settings) |
| `GET /terminal/{sessionId}/logs` | `getLogs` | `LogInfo {logStatus, logs}` | yes (polled every second) |
| `GET /terminal/{sessionId}/result` | `getSqlResult` | list of `SqlResult` | |
| `PUT /terminal/{sessionId}/stop` | `stopSql` | `ok()` | |
| `GET /terminal/latestInfos/` | `getLatestInfo` | `LatestSessionInfo` for this `JSESSIONID` | |
| `GET /terminal/examples`, `GET /terminal/examples/{exampleName}` | `getExamples`, `getSqlExamples` | `SqlExample` names / SQL | |

**Logs, files, others**

| Method + path | Handler | Returns | OLake-UI |
| --- | --- | --- | --- |
| `GET /logs/process/{processId}` | `LogController.getProcessLogs` | `{processId, exists, driverLog{exists, content}, taskLogs[{taskId, exists, content}]}` | yes (forwarded) |
| `GET /logs/process/{processId}/download` | `downloadProcessLogs` | tar stream `compaction-logs-<id>.tar` of all `*.log` | yes (forwarded) |
| `GET /logs/process/{processId}/file/{fileId}` | `downloadLogFile` | one `<fileId>.log` as text (`driver` or a task id) | yes (forwarded) |
| `POST /files` (multipart field `file`) | `PlatformFileInfoController.uploadFile` | `{id, url}`; `.xml` must parse as Hadoop config | |
| `GET /files/{fileId}` | `downloadFile` | raw bytes | |
| `GET /settings/system` | `SettingController.getSystemSetting` | all config keys sorted; `admin-password` and the DB password masked | |
| `GET /settings/containers` | `getContainerSetting` | containers with their groups | |
| `GET /overview/summary`, `/resource?startTime=`, `/optimizing`, `/dataSize?startTime=`, `/top?order=&orderBy=&limit=` | `OverviewController` | cached overview data; `orderBy` is `tableSize`, `fileCount` or `healthScore` | |
| `GET /health/status` | `HealthCheckController.healthCheck` | `{"ams_ha_state": haState}` (key `AmsServiceMetrics.AMS_HA_STATE_NAME`) | |
| `GET /versionInfo` | `VersionController.getVersionInfo` | `VersionInfo {version, commitTime}` | forwarded if called; `CLAUDE.md` uses it to check the forwarder |
| `PUT /telemetry/install-id` | `TelemetryController.setInstallId` | `{install_id}`; id must match `[A-Za-z0-9_-]+`, at most 128 chars | |

### Main flows

- **Register a catalog** (`createCatalog`). Parse `CatalogRegisterInfo` → `validateCatalogRegisterInfo`
  (non-null maps, non-empty `tableFormatList`, `CatalogDescriptor.of(info).validate()` against
  `VALIDATE_CATALOGS`, `checkHiddenProperties`, the required property per type in
  `CATALOG_REQUIRED_PROPERTIES`) → reject duplicate names → `constructCatalogMeta` (merges table
  properties under the `table.` prefix, sets `catalog-impl` for Glue and REST, writes
  `table.self-optimizing.group` from `optimizerGroup`, writes `table-formats`, copies auth and
  storage config; for AK/SK the access and secret key go into catalog properties) →
  `CatalogManager.createCatalog`. OLake-UI sends `tableFormatList: ["ICEBERG"]`.
- **Read a catalog** (`getCatalogDetail`). Rebuilds a `CatalogSettingInfo` from `CatalogMeta`, then
  `removeHiddenProperties` and `maskSensitiveData` (secret key replaced by `********`).
- **Update a catalog** (`updateCatalog`). `unMaskSensitiveData` puts back the stored
  `s3.secret-access-key` if the client sent the mask, then the same `constructCatalogMeta`.
- **Table detail** (`getTableDetail`). `ServerTableDescriptor.getTableDetail` reads Iceberg
  metadata; then the table runtime (`TableManager.getTableRuntimeMata`) adds status and health
  scores. A table with no runtime row gets `optimizingStatus = IDLE`.
- **Runs list** (`getOptimizingProcesses`). `page`/`pageSize` become `offset`/`limit`;
  `MixedAndIcebergTableDescriptor.getOptimizingProcessesInfo` pages in SQL with PageHelper.
- **Cancel a Run** (`cancelOptimizingProcess`). Checks that the process belongs to the table, then
  calls the server's own Thrift optimizing service at `AmsUtil.getAMSThriftAddress(...)`.
- **Table settings from OLake-UI.** OLake-UI posts
  `ALTER TABLE db.t SET TBLPROPERTIES (...)` to `executeScript`, then polls `getLogs` until
  `logStatus` is no longer `Running` (5-minute timeout in OLake-UI). See
  [`terminal`](../../terminal/README.md).

## State, threads and concurrency

- Handlers run on Jetty request threads; controllers are singletons shared by all requests.
- `LogController.LOG_BASE_DIR` is read once from env `LOG_DIR`, default
  `/mnt/amoro-logs/compaction`. OLake-UI's `docker-compose-v1.yml` sets
  `LOG_DIR: /mnt/amoro-logs/compaction`.
- `downloadProcessLogs` writes the tar directly to the servlet output stream; an I/O error after
  headers are sent is only logged.

## Contracts

- **Envelope.** Success: `{"code":200,"message":"success","result":...}` (`OkResponse`). Errors:
  `{"code":<n>,"message":...,"requestId":""}` (`ErrorResponse`) written by
  `DashboardServer.handleException`: 403 `"Please login first"` (`ForbiddenException`), 403
  `"Signature check failed"` (`SignatureCheckException`), otherwise 500 with the exception message.
  The handler sets the `code` field, not the HTTP status, so clients must read `code`. OLake-UI's
  client refreshes its token only on HTTP 401, which this code never sends.
- **Auth.** See [`../../authentication`](../../authentication/README.md) and
  [`..`](../README.md): a request is accepted if its path is in `URL_WHITE_LIST`, or it carries
  `X-Request-Source: Web` and a session with attribute `user`, or a valid API-token signature
  (`ApiTokenController.checkApiToken`, default `http-server.rest-auth-type: token`).
- **Signature.** `signature = md5(apiKey + encryptString + secret)` where `encryptString` is the
  query params without `apiKey` and `signature`, sorted by key, each as `key + URL-decoded first
  non-blank value`, concatenated; if empty, today's date as `yyyyMMdd`. Only the query string is
  signed, not the body or the path. OLake-UI's `calculateSignature` in `client.go` must stay
  identical.
- **Terminal.** `logStatus` is an `ExecutionStatus` name (`Created`, `Running`, `Finished`,
  `Failed`, `Canceled`, `Expired`). OLake-UI treats `Finished` as success.
- **Fields OLake-UI parses.** `result.apikey` and `result.secret` from `/api/token/create`;
  `result.sessionId` from execute; `result.logStatus` and `result.logs` from logs; the
  `ServerTableMeta` fields from details (size, properties, health score); the
  `OptimizingProcessInfo` fields from optimizing-processes. Renaming a getter in these classes
  renames the JSON field.

## Extending or changing it

1. Add a `public void myHandler(Context ctx)` method to the controller for that area (or a new
   controller, constructed in `DashboardServer`'s constructor).
2. Read input with `ctx.pathParam`, `ctx.queryParamAsClass(...).getOrDefault(...)` or
   `ctx.bodyAsClass(...)`; validate with `Preconditions` (a failure becomes a 500 envelope).
3. Answer with `ctx.json(OkResponse.of(result))`, and `PageResult.of(list, offset, limit)` for
   lists. `TableController.getTableTags` is a short example to copy.
4. Register the route in `DashboardServer.apiGroup()`. Do not add it to `URL_WHITE_LIST` unless it
   must work without auth.
5. If the path or response shape is something OLake-UI calls, grep `olake-ui/server` and
   `olake-ui/ui/src` and report what breaks.

## Invariants and pitfalls

- `VALIDATE_CATALOGS` holds only `ICEBERG` combinations (types `ams`, `hive`, `hadoop`,
  `filesystem`, `glue`, `custom`, `rest`). `hive` is the Hive Metastore catalog for Iceberg tables.
  An unknown name in `tableFormatList` makes `TableFormat.valueOf` return `null`, so the request
  fails `CatalogDescriptor.validate()` ("Not support ...").
- `getCatalogDetail` reports a `hadoop` catalog without `table-formats` as type `ams`. Loading an
  `ams` catalog without `table-formats` fails in `CatalogUtil.tableFormats`; `createCatalog`
  always writes the property.
- `getCatalogs` returns raw `CatalogMeta`, including catalog properties such as
  `s3.secret-access-key` for AK/SK catalogs; only `getCatalogDetail` masks it.
- `unMaskSensitiveData` always restores `s3.secret-access-key`, also for OSS storage.
- `CatalogDescriptor.of` checks only the first entry of `tableFormatList`.
- `LogController` builds paths from `processId` and `fileId` without validating them.
- `OptimizerGroupController.getOptimizers` reports `jobStatus` `"RUNNING"` for every registered
  optimizer; it does not check the container.
- `LoginController.logout` only removes the cookie; the session row stays valid until it expires
  (`http-server.session-timeout`, 7 days).
- `TableController.getTableDetailTabToken` exists but has no route.
- `ApiTokenController.createApiToken` adds a row each time; nothing deletes tokens OLake-UI created.

## Tests

- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestLoginAndApiToken.java` covers the
  OLake-UI flow: password login, token creation, signature check with OLake-UI's formula.
- Run one test:
  `./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestLoginAndApiToken -Dsurefire.failIfNoSpecifiedTests=false`
- For route behaviour, run the server from source and call it with `curl` (see `CLAUDE.md`,
  section 5).

## Related packages

- [`dashboard`](../README.md): `DashboardServer`, routing, auth filter, table descriptor.
- [`dashboard/model`](../model/README.md), [`dashboard/response`](../response/README.md),
  [`dashboard/utils`](../utils/README.md)
- [`authentication`](../../authentication/README.md), [`terminal`](../../terminal/README.md)
