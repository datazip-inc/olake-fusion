# `io.olake.fusion.server.dashboard`

Fusion's HTTP layer: the route table and auth filter for `/api/ams/v1/...` (`DashboardServer`),
the JSON mapper, and the server-side services the REST controllers use to read table metadata,
API tokens, uploaded files and the overview cache.

## Role in Fusion

- `FusionServiceContainer.initHttpService()` builds one `DashboardServer` and one Javalin 4
  (`javalin.version` 4.6.8) server. It registers:
  - `dashboardServer.configStaticFiles()` (the `fusion-web` bundle from classpath `/static`);
  - a JDBC-backed Jetty session handler (`HttpSessionHandlerFactory`, table `http_session` in
    PostgreSQL), so login sessions survive restarts;
  - `JavalinJsonMapper.createDefaultJsonMapper()`;
  - routes from `dashboardServer.endpoints()` and from `RestCatalogService` (Iceberg REST catalog);
  - a `before` filter: if the query has `token`, `CommonUtil.checkSinglePageToken(ctx)`, otherwise
    `dashboardServer.preHandleRequest(ctx)`;
  - an exception mapper: `dashboardServer.handleException` for everything that is not an Iceberg
    REST catalog request.
- Callers: OLake-UI's Go backend (API-token signed calls), Fusion's own web UI (session), and
  anyone reaching port `http-server.bind-port`.
- It calls `CatalogManager`, `TableManager`, `OptimizerManager`, `TerminalManager` and the
  persistence mappers.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `DashboardServer` | Builds all controllers; defines every route (`endpoints()`, `apiGroup()`); auth filter `preHandleRequest`; error mapping `handleException`; serves `static/index.html` for UI paths and `openapi/openapi.yaml` at `/swagger-docs` | controllers in [`controller`](controller/README.md), `HttpAuthenticationFactory`, `ApiTokenController` |
| `ServerTableDescriptor` | Loads a table through its `ServerCatalog` and dispatches to the `FormatTableDescriptor` registered for its format | `CatalogManager`, `ServiceLoader<FormatTableDescriptor>`, `ThreadPools.getWorkerPool()` |
| `MixedAndIcebergTableDescriptor` | The only `FormatTableDescriptor` (registered in `META-INF/services/io.olake.fusion.table.descriptor.FormatTableDescriptor`); `supportFormat()` returns only `ICEBERG`. Reads Iceberg metadata for details, snapshots, snapshot files, partitions, files, DDL history, tags/branches; reads optimizing processes and tasks from the database | Iceberg API, `TableStatCollector`, `DDLReverser` + `IcebergTableMetaExtract`, `TableProcessMapper`, `OptimizingProcessMapper` |
| `OverviewManager` | Cache for the overview page: catalog/table counts, data size, CPU/memory history, status counts, top tables. Refreshed by a scheduler | `CatalogMetaMapper`, `TableRuntimeMapper`, `TableMetaMapper`, `OptimizerMapper` |
| `APITokenManager` | CRUD for API tokens (`api_tokens` via `ApiTokensMapper`) | `PersistentBase` |
| `PlatformFileManager` | Stores uploaded files base64-encoded (`PlatformFileMapper`) | `PersistentBase` |
| `JavalinJsonMapper` | Jackson mapper for Javalin, with `TableFormat` (de)serializers; a `String` result is written as-is | Jackson |
| `ServerTableProperties` | Sets of property names `HIDDEN_EXPOSED` and `HIDDEN_INTERNAL`; neither is read anywhere | `TableProperties` |

## How it works

### Request filter (`preHandleRequest`)

1. Path in `URL_WHITE_LIST` (exact match, or prefix match for entries ending in `*`): allowed.
   The list includes `/api/ams/v1/versionInfo`, `/api/ams/v1/login`, `/api/ams/v1/login/current`,
   `/api/ams/v1/health/status`, the two `/api/ams/v1/api/token/calculate/*` helpers, UI page paths,
   `/assets/*`, `/swagger-docs` and the Iceberg REST prefix `RestCatalogService.ICEBERG_REST_API_PREFIX + "/*"`.
2. Header `X-Request-Source: Web` (case-insensitive value): the session must hold attribute `user`
   (set by `LoginController.login`), else `ForbiddenException`.
3. Otherwise, by `http-server.rest-auth-type`:
   - `basic`: `basicAuthProvider.authenticate(HttpAuthenticationFactory.getPasswordCredential(...))`
     with the provider class from `http-server.auth-basic-provider`;
   - `jwt`: `jwtAuthProvider.authenticate(getBearerTokenCredential(...))`, provider from
     `http-server.auth-jwt-provider`;
   - anything else (default `token`, also in `local-test/config.yaml`):
     `apiTokenController.checkApiToken(ctx)`. This is the path OLake-UI uses.

The `before` filter in `FusionServiceContainer` runs `CommonUtil.checkSinglePageToken` **instead
of** this method when the request has a `token` query parameter. See "Invariants and pitfalls".

### Error mapping (`handleException`)

- `ForbiddenException`: non-API paths get `index.html`; `/api/ams...` gets
  `ErrorResponse(403, "Please login first")`.
- `SignatureCheckException`: `ErrorResponse(403, "Signature check failed")`.
- Anything else: `ErrorResponse(500, e.getMessage())`.
- 404 and 500 without an exception get `"page not found!"` / `"internal error!"`
  (`httpServer.error` in `FusionServiceContainer`).

### Table metadata (`MixedAndIcebergTableDescriptor`)

`FusionTable.originalTable()` is cast to `MixedTable` and read through `asUnkeyedTable()`.
`getTableBasicInfo` throws `IllegalStateException("unknown type of table")` for anything that is
not an unkeyed table.

- `getTableDetail`: basic statistics via `TableStatCollector.fillTableStatistics` (from the
  current snapshot's `total-files-size`, `total-data-files` + `total-delete-files`), schema,
  partition fields, identifier fields as `pkList`, properties without `READ_PROTECTED_PROPERTIES`,
  and a `TableSummary` with file count, size, average size, records (`total-records` of the latest
  snapshot) and format such as `Iceberg(V2)` (`decorateTableFormat`).
- `getSnapshots`: walks ancestors of the ref's snapshot (default `main`), skips snapshots whose
  summary has `TRANSACTION_BEGIN_SIGNATURE`, filters by `OperationType` (`OPTIMIZING` means the
  producer is `CommitMetaProducer.OPTIMIZE`), sorts newest first.
- `getSnapshotDetail`: added/removed data and delete files of one snapshot.
- `getTablePartitions`: reads the `PARTITIONS` metadata table; on any error falls back to
  `collectPartitionsFromFileScan` (scans manifests). File size counts data files only.
- `getTableFiles`: manifest entries via `IcebergFindFiles`, optionally filtered to one partition.
- `getTableOperations`: `DDLReverser` over all `metadata.json` versions (see
  [`component/reverser`](component/reverser/README.md)).
- `getOptimizingProcessesInfo`: looks up the `ServerTableIdentifier`, pages
  `TableProcessMapper.listProcessMeta(tableId, type, status)` with `PageHelper.startPage`, loads
  tasks with `OptimizingProcessMapper.selectOptimizeTaskMetas`, and builds `OptimizingProcessInfo`
  (task counts, input/output files, duration, status, fail reason, summary).
- `getOptimizingTaskInfos`: one `OptimizingTaskInfo` per task of a process.

### Overview cache (`OverviewManager`)

A single daemon thread `overview-refresh-scheduler-%d` runs `refresh()` every
`overview-cache.refresh-interval` (default 180 s; not scheduled when the interval is 0).
`refreshTableCache` rebuilds counts, top tables and status counts from `table_runtime`;
`refreshResourceUsage` sums optimizer threads and memory. History deques are capped at
`overview-cache.max-size` (default 3360) entries.

## State, threads and concurrency

- `DashboardServer.indexHtml` is loaded lazily with double-checked locking.
- `ServerTableDescriptor` shares `ThreadPools.getWorkerPool()` with all descriptors for manifest
  scans.
- `OverviewManager` fields are atomics and concurrent deques, except `allTopTableItem`, a plain
  `ArrayList` that `refresh()` clears and refills while HTTP threads copy it in
  `getAllTopTableItem()`. A request during a refresh can see a partial list.
- `getOptimizingStatus()` returns the live map; the refresh clears and refills it.

## Contracts

- Route paths, the `OkResponse`/`ErrorResponse` envelope, and the JSON names of the classes in
  [`model`](model/README.md) and `io.olake.fusion.table.descriptor` are OLake-UI's contract. See the
  route table in [`controller`](controller/README.md).
- Config keys read here: `http-server.rest-auth-type`, `http-server.auth-basic-provider`,
  `http-server.auth-jwt-provider`, `http-server.login-auth-provider` (in `LoginController`),
  `http-server.proxy-client-ip-header` (default `X-Real-IP`), `overview-cache.refresh-interval`,
  `overview-cache.max-size`. In `config.yaml` they sit under `ams:`.

## Extending or changing it

- New endpoint: handler in a controller, route in `apiGroup()`, response via `OkResponse` (see
  [`controller`](controller/README.md)).
- New table read: add the method to `FormatTableDescriptor` (`fusion-core`), implement it in
  `MixedAndIcebergTableDescriptor`, and add a pass-through in `ServerTableDescriptor` that calls
  `loadTable` and the descriptor, as `getTableTags` does.

## Invariants and pitfalls

- **Auth bypass through `token`.** When a request has a `token` query parameter,
  `FusionServiceContainer` calls only `CommonUtil.checkSinglePageToken` and skips
  `preHandleRequest`. That check accepts the request if the URI contains `/login/current` or
  `/versionInfo`, or if `token` equals `CommonUtil.generateTablePageToken(catalog, db, table)`, an
  unsalted MD5 of the three names, which may be passed as query parameters. Anyone who can reach
  the port can compute such a token and call any `/api/ams/v1` route (terminal SQL, catalog reads,
  token creation) without credentials. Treat this as a security issue before you rely on the auth
  filter.
- `ServerTableDescriptor` looks up the descriptor by `amoroTable.format()` without a null check;
  only `ICEBERG` is registered.
- `getTableConsumerInfos` always returns an empty list.
- `ServerTableProperties.HIDDEN_EXPOSED` and `HIDDEN_INTERNAL` are not read anywhere.

## Tests

- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestIcebergServerTableDescriptor.java`
- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestOverviewManager.java`
- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestLoginAndApiToken.java`
- Run one:
  `./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestOverviewManager -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`controller`](controller/README.md), [`model`](model/README.md),
  [`response`](response/README.md), [`utils`](utils/README.md),
  [`component/reverser`](component/reverser/README.md)
- [`authentication`](../authentication/README.md), [`terminal`](../terminal/README.md)
- [`catalog`](../catalog/README.md), [`table`](../table/README.md),
  [`persistence`](../persistence/README.md), [`resource`](../resource/README.md)
