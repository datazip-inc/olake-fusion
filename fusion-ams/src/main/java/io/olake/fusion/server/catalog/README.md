# `io.olake.fusion.server.catalog`

Registers, validates, stores and loads the catalogs Fusion compacts tables from, and gives the rest of the server one `ServerCatalog` object per catalog to list and load Iceberg tables.

## Role in Fusion

- `FusionServiceContainer.startRestServices()` creates one `DefaultCatalogManager(serviceConfig)` on every node and passes it to `DefaultTableManager`, `DefaultOptimizerManager`, `TerminalManager`, and later `DefaultTableService` and `DefaultOptimizingService`.
- The dashboard `CatalogController` (behind OLake-UI's `POST/GET/PUT/DELETE /api/opt/v1/catalog...` routes, which call Fusion's catalog API) turns the REST request into a `CatalogMeta` (it writes `tableFormatList` into the `table-formats` property and `optimizerGroup` into `table.self-optimizing.group`) and calls `createCatalog`, `updateCatalog`, `dropCatalog`.
- `table.DefaultTableService.exploreTableRuntimes` calls `getServerCatalogs()` every `refresh-external-catalogs.interval` and lists tables from each one.
- `CatalogManager.loadTable(TableIdentifier)` is how optimizing, scheduler and dashboard code get a `FusionTable`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `CatalogManager` | Interface: `listCatalogMetas`, `getCatalogMeta`, `catalogExist`, `getServerCatalog`, `getInternalCatalog`, `getServerCatalogs`, `createCatalog`, `dropCatalog`, `updateCatalog`, `loadTable`. | — |
| `DefaultCatalogManager` | Implementation: `catalog_metadata` access with a Guava `LoadingCache` of `CatalogMeta`, a map of built `ServerCatalog`s, connection test on create/update, telemetry. | `CatalogMetaMapper`, `CatalogBuilder`, `CatalogConnectionTester`, `Telemetry` |
| `CatalogBuilder` | `checkSupported(meta)` (type in the supported set and table formats ⊆ `{ICEBERG}`); `buildServerCatalog(meta, conf)`. | `CatalogUtil.tableFormats` (fusion-core) |
| `CatalogConnectionTester` | `runTestConnection(meta)`: builds a raw Iceberg catalog, creates namespace `test_olake` and table `test_olake` if missing, and appends one Parquet record. | Iceberg `CatalogUtil.buildIcebergCatalog`, `StaticAwsCredentialsProvider` |
| `ServerCatalog` | Abstract base: holds `CatalogMeta` and `TableMetaStore`; `updateMetadata`, `reload`, abstract list/load methods. | `CatalogMetaMapper`, `CatalogUtil.buildMetaStore` |
| `ExternalCatalog` | Catalog whose tables live in an outside metastore (`glue`, `rest`, `hive`, `custom`). Delegates to `CommonUnifiedCatalog`, applies database/table filters, writes `table_identifier` rows (`syncTable`, `disposeTable`). | `UnifiedCatalog`, `TableMetaMapper` |
| `InternalCatalog` / `InternalCatalogImpl` | Catalog type `ams`: databases and tables kept in Fusion's database and served over Iceberg REST. Not used by OLake-UI. | `table.internal`, `TableMetaMapper`, `CatalogMetaMapper` |
| `CatalogType` | Enum `HIVE, GLUE, REST, CUSTOM`; used by `terminal` code only. | — |

## How it works

### Startup

The `DefaultCatalogManager` constructor builds the meta cache (`maximumSize(100)`, `expireAfterWrite(catalog-meta-cache.expiration-interval)`, loader `CatalogMetaMapper.getCatalog`) and then, for each row from `CatalogMetaMapper.getCatalogs`, calls `CatalogBuilder.buildServerCatalog`. A catalog that fails to build is logged and skipped, so one bad row never stops the server (`TestIcebergOnlyCatalogService.storedNonIcebergCatalogIsSkippedWhenCatalogsLoad`).

### Registration (`createCatalog`)

1. `AlreadyExistsException` if `catalogExist(name)`.
2. `CatalogBuilder.checkSupported(meta)`: type must be one of `glue`, `rest`, `custom`, `hive`, `ams`, and `CatalogUtil.tableFormats(meta)` must contain only `ICEBERG`. Without a `table-formats` property the formats come from the type: `ams` is rejected (`CatalogUtil.tableFormats` throws `IllegalArgumentException`), every other type gives `ICEBERG`; so `ams` catalogs must set `table-formats=ICEBERG`.
3. `fillCatalogProperties`: for `hive` only, sets `ams.uri` to the Thrift table-service address and defaults `CLIENT_POOL_SIZE`.
4. `validateCatalogConnection` → `CatalogConnectionTester.runTestConnection`. The test catalog gets its properties from `CatalogUtil.withIcebergCatalogInitializeProperties`, which sets `io-impl=org.apache.iceberg.io.ResolvingFileIO` for `hive` and `custom` catalogs that set no `io-impl`. On failure it throws `RuntimeException("Test Connection unsuccessful: <root cause>\n<stack trace>")`.
5. Builds the `ServerCatalog`, inserts the row with `CatalogMetaMapper.insertCatalog`, disposes any stale in-memory catalog of that name and stores the new one.
6. `Telemetry.trackFailure` / `trackCatalogCreated` record the outcome; `olake_created` in the catalog properties is reported as the OLake flag.

### Update (`updateCatalog`)

`getServerCatalog(name)`, reject a type change (the stored and new catalog type strings must be equal), `checkSupported`, run the connection test on a deep copy with `fillCatalogProperties` applied, then `catalog.updateMetadata(meta)` (writes `CatalogMetaMapper.updateCatalog`, rebuilds the `TableMetaStore`, and for `ExternalCatalog` refreshes the unified catalog and filters) and invalidates the cache entry. Note that the stored meta is the one passed in, without `fillCatalogProperties`.

### Drop (`dropCatalog`)

In one `doAs`, reads the row; for `ams` catalogs refuses when `database_count` or `table_count` > 0; calls `deleteCatalog` (which only deletes rows with both counts 0) and invalidates the cache; then `disposeCatalog`. Table runtimes and `table_identifier` rows of the dropped catalog are removed later by `DefaultTableService.exploreTableRuntimes`, not here.

### Loading

`getServerCatalog(name)` reads the cached meta; if the catalog is gone it disposes the in-memory object and throws `ObjectNotExistsException`. Otherwise it `computeIfAbsent`s the built catalog and calls `reload(meta)`, which rebuilds only when the meta differs (`CatalogMeta.equals`). `getServerCatalogs()` does the same for every row from `listCatalogMetas()` and skips catalogs that fail to build.

`ExternalCatalog.listDatabases` filters by `database-filter`; `listTables(db)` filters `<db>.<table>` by `table-filter` (both Java regexes, full match). Every catalog call runs inside `metaStore.doAs(...)`.

## State, threads and concurrency

- `serverCatalogMap` is a `ConcurrentHashMap`; `metaCache` is a Guava cache. `ServerCatalog.metadata` and `metaStore` are volatile.
- Catalog metadata changed on another node becomes visible after the cache entry expires (default 60 s).
- `ExternalCatalog` filter patterns are plain fields reassigned in `catalogMetadataChanged`.

## Contracts

- Table `catalog_metadata` (`catalog_id` serial PK, unique `catalog_name`, `catalog_metastore`, `storage_configs`, `auth_configs`, `catalog_properties`, `database_count`, `table_count`). Mapper: `CatalogMetaMapper.getCatalogs`, `getCatalog`, `insertCatalog`, `updateCatalog`, `deleteCatalog`, `selectTableCount`, `selectDatabaseCount`, `inc/decTableCount`, `inc/decDatabaseCount`.
- Catalog properties read here: `table-formats`, `database-filter`, `table-filter`, `warehouse` (internal tables), `ams.uri`, `olake_created`. Properties prefixed `table.` become table defaults (merged in fusion-core `BasicUnkeyedTable.properties()`), for example `table.self-optimizing.enabled`, `table.self-optimizing.quota`, `table.self-optimizing.group`.
- Config key: `catalog-meta-cache.expiration-interval` (default 60 s).
- Registering a catalog creates `test_olake.test_olake` in it and appends one row each time `createCatalog` or `updateCatalog` runs.

## Extending or changing it

- Supported types live in `CatalogBuilder.SUPPORTED_CATALOG_TYPES` and the `switch` in `buildServerCatalog`; both must change together, and OLake-UI's `mapper.go` decides which types it sends.
- Keep `checkSupported` the single gate for Iceberg-only; it runs on create, update and every build.
- A change to the REST payload shape belongs in `CatalogController`; grep OLake-UI's `server/internal/services/optimization` for callers.

## Invariants and pitfalls

- Never log `storage_configs` or `auth_configs`; this package logs only names and types. Both are stored as plain JSON in `catalog_metadata`.
- The connection-test failure message includes the full stack trace and is returned to the API caller.
- `test_olake.test_olake` is not excluded from discovery; the table explorer creates a runtime for it like any other table unless a `database-filter` excludes it.
- A failed catalog build at startup or in `getServerCatalogs` is skipped with a warning, so its tables are neither synced nor disposed until it builds again.
- `InternalCatalogImpl.getMetadata()` adds `uri` and `catalog-impl` to the shared `CatalogMeta` object before returning a deep copy.
- Mixed-format and Hive-table code paths reachable through `CommonUnifiedCatalog` are out of scope.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/catalog/`: `TestCatalogBuilder`, `TestIcebergOnlyCatalogService`, `TestServerCatalog`, `TableCatalogTestBase`; also `table/TestCatalogService`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestCatalogBuilder -Dsurefire.failIfNoSpecifiedTests=false
```

Service tests use embedded Derby (`AMSManagerTestBase`), not PostgreSQL.

## Related packages

- [`table`](../table/README.md): table discovery uses `getServerCatalogs()`.
- [`table/internal`](../table/internal/README.md): internal (`ams`) tables.
- [`persistence/mapper`](../persistence/mapper/README.md): `CatalogMetaMapper`, `TableMetaMapper`.
- `../dashboard/controller/CatalogController`, `../terminal`.
