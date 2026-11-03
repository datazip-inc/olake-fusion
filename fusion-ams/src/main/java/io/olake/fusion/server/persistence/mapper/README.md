# `io.olake.fusion.server.persistence.mapper`

The MyBatis mapper interfaces: every SQL statement Fusion's server runs against PostgreSQL, written as annotations (`@Select`, `@Insert`, `@Update`, `@Delete`) on Java interfaces.

## Role in Fusion

- All eleven mappers are registered in `persistence.SqlSessionFactoryProvider.init` (`configuration.addMapper(...)`).
- Callers never get a mapper directly; they use `PersistentBase` helpers, for example `getAs(TableMetaMapper.class, m -> m.selectAllTableIdentifiers())` or `doAs(TableRuntimeMapper.class, m -> m.insertRuntime(meta))`. Session and transaction rules are in [`persistence`](../README.md).
- Column type conversion uses handlers from [`converter`](../converter/README.md); IN lists use [`extension`](../extension/README.md).
- The schema is `fusion-ams/src/main/resources/postgres/ams-postgres-init.sql` plus `postgres/migration/V*.sql`.

## Key classes

| Mapper | Tables | Main callers |
| --- | --- | --- |
| `CatalogMetaMapper` | `catalog_metadata` | `catalog.DefaultCatalogManager`, `ServerCatalog`, `InternalCatalog` |
| `TableMetaMapper` | `table_identifier`, `table_metadata`, `database_metadata` | `table.DefaultTableService`, `DefaultTableManager`, `DefaultTableRuntimeStore`, `catalog.ExternalCatalog`, `InternalCatalog`, `table.internal.IcebergInternalTableOperations` |
| `TableRuntimeMapper` | `table_runtime`, `table_runtime_state` | `table.DefaultTableService`, `DefaultTableRuntimeStore`, `DefaultTableManager` |
| `TableProcessMapper` | `table_process` | `table.DefaultTableRuntime`, `optimizing/OptimizingQueue`, `process/ProcessService`, `scheduler/inline/OptimizingExpiringExecutor`, dashboard `MixedAndIcebergTableDescriptor` |
| `OptimizingProcessMapper` | `optimizing_process_state`, `task_runtime`, `optimizing_task_quota` | `optimizing/OptimizingQueue`, `optimizing/TaskRuntime`, `persistence.TaskFilesPersistence`, `table.DefaultTableRuntime`, `DefaultTableManager`, `OptimizingExpiringExecutor` |
| `TableBlockerMapper` | `table_blocker` | `table.DefaultTableManager`, `DefaultTableRuntime`, `OptimizingQueue`, `BlockerExpiringExecutor`, `InternalCatalog` |
| `OptimizerMapper` | `optimizer` | `DefaultOptimizingService`, `DefaultTableManager`, `DefaultTableRuntime` |
| `ResourceMapper` | `resource_group`, `resource` | `DefaultOptimizerManager`, `DefaultOptimizingService` |
| `ApiTokensMapper` | `api_tokens` | `APITokenManager`, `ApiTokenController` |
| `PlatformFileMapper` | `platform_file` | `PlatformFileManager` (used by `PlatformFileInfoController`, `CatalogController`) |
| `PlatformPropertyMapper` | `platform_property` (created by migration `V1__platform_property.sql`) | `persistence.PlatformPropertyStore` |

## How it works

### Table runtime (`TableRuntimeMapper`)

- `insertRuntime(meta)` writes `table_id, group_name, status_code, table_config, table_summary, bucket_id`; `status_code_update_time` takes the column default `now()`.
- `updateRuntime(meta)` rewrites every column for one `table_id`; `updateTableSummary(meta)` writes only `table_summary`.
- `selectRuntime`, `selectAllRuntimes` share the `@Results(id = "tableRuntimeMeta")` map.
- `queryForGroups(groupName, fuzzyDbName, fuzzyTableName, statusCodeFilter)` joins `table_identifier`, uses `_databaseId` binds to pick `CONCAT` (MySQL) or `||` (Postgres/Derby) for `LIKE`, and orders by `status_code, status_code_update_time DESC`. Paging is added by PageHelper in `DefaultTableManager`.
- State rows: `saveState` (version 0), `getState`, `selectAllStates`, `setStateValue` (sets value and `state_version = state_version + 1`, keyed by `(table_id, state_key)`), `removeState`, `removeAllTableStates`.

### Identifiers and internal metadata (`TableMetaMapper`)

- `insertTable(ServerTableIdentifier)` uses `useGeneratedKeys` to fill `tableIdentifier.id` from the BIGSERIAL `table_id`.
- Lookups: `selectTableIdentifier(catalog, db, table)`, `selectTableIdentifiersByDb`, `selectTableIdentifiersByCatalog`, `selectAllTableIdentifiers`, `selectTableIdentifiers(List<Long>)` (IN list), `getTableIdentifier(long)`.
- Deletes: `deleteTableIdById`, `deleteTableIdByName`.
- Internal catalogs only: `insertTableMeta`, `selectTableMetaById`, `selectTableMetaByName`, `selectTableMetasByDb`, `deleteTableMetaById`, `commitTableChange` (optimistic `meta_version` check), and `database_metadata` methods `insertDatabase`, `selectDatabases`, `selectDatabase`, `dropDb`, `incTableCount`, `decTableCount`, `selectTableCount`.

### Processes (`TableProcessMapper`)

`insertProcess` and `updateProcess` take explicit parameters (status as `ProcessStatus`, stored by enum name); `updateProcessType`; `getProcessMeta` (`@Results(id = "tableProcessMap")`); `listProcessMeta(tableId, processType, status)` ordered by `process_id desc`; `selectTableMaxProcessIds` (IN list, `GROUP BY table_id`); `selectAllActiveProcesses` (`status in ('SUBMITTED', 'RUNNING')`); `deleteBefore`.

### Optimizing (`OptimizingProcessMapper`)

`insertInternalProcessState`, `getProcessState`, `selectProcessInputFiles` / `updateProcessInputFiles` (gzip blob via `Object2ByteArrayConvert`), `deleteProcessStateBefore`; `insertTaskRuntimes` (multi-row insert via `<foreach>`), `selectTaskRuntimes`, `updateTaskRuntime`, `selectOptimizeTaskMetas`, `deleteTaskRuntimesBefore`; `insertTaskQuota`, `selectTaskQuotasByTime`, `selectTableQuotas` (IN list), `deleteOptimizingQuotaBefore`. The `*Before` deletes are used by `OptimizingExpiringExecutor` to trim history.

### Blockers (`TableBlockerMapper`)

`selectAllBlockers(now)`, `selectBlockers(catalog, db, table, now)`, `selectBlocker(id, now)` return only unexpired rows; `insert` (generated `blocker_id`), `renewBlocker` (only if not expired), `deleteBlocker`, `deleteExpiredBlockers`, `deleteTableBlockers`.

## State, threads and concurrency

Mappers are stateless proxies bound to the calling thread's session. Concurrency control is in SQL:

- `table_blocker` unique index `uq_prev` (blocker creation),
- `table_metadata.meta_version` (`commitTableChange`),
- unique `table_identifier (catalog_name, db_name, table_name)` and `table_runtime_state (table_id, state_key)`.

## Contracts

Main Fusion tables and their keys (from `ams-postgres-init.sql`):

| Table | Key | Notes |
| --- | --- | --- |
| `catalog_metadata` | `catalog_id` serial, unique `catalog_name` | `database_count`, `table_count` only change for `ams` catalogs |
| `table_identifier` | `table_id` BIGSERIAL | the id used everywhere as table id |
| `table_runtime` | `table_id` | `status_code` default 700 (IDLE) |
| `table_runtime_state` | `state_id`, unique `(table_id, state_key)` | |
| `table_process` | `process_id` (snowflake id set by the caller) | `fail_message` limited to 4096 chars by a CHECK; index `(table_id, create_time)` |
| `optimizing_process_state` | `process_id` | `rewrite_input` bytea |
| `task_runtime` | `(process_id, task_id)` | |
| `optimizing_task_quota` | `(process_id, task_id, retry_num)` | |
| `table_blocker` | `blocker_id`, unique `uq_prev` | |
| `platform_property` | `property_key` | from migration V1 |

OLake-UI depends indirectly on `table_process` (run history via `/api/ams/v1/.../optimizing-processes`) and `table_runtime.table_summary` (table details: size, health score).

## Extending or changing it

1. Add the method to the right interface. Copy a neighbour: `TableBlockerMapper.selectBlocker` for a single-row select with `@Results`, `TableRuntimeMapper.selectAllRuntimes` with `@ResultMap` to reuse a map, `TableMetaMapper.insertTable` for generated keys.
2. Name every parameter with `@Param` and give each map/list/timestamp column its `typeHandler`.
3. Write plain SQL that runs on PostgreSQL; no MySQL-only syntax (`ON DUPLICATE KEY`, backticks).
4. A new table or column needs a migration script (see [`persistence`](../README.md)), not only an init-script change.
5. A new mapper interface must be added to `SqlSessionFactoryProvider.init`.
6. Call it through `PersistentBase` from a class that extends it.

## Invariants and pitfalls

- IN-list methods fail on an empty collection (`IN ()`).
- `selectAllActiveProcesses` matches status names as string literals; renaming `ProcessStatus` values breaks it and stored rows.
- `database_metadata` count methods filter by `db_name` only (internal catalogs; out of OLake-UI's path).
- `CatalogMetaMapper.deleteCatalog` deletes only when both counts are 0 and the caller does not check the result.
- `catalog_metadata.storage_configs` and `auth_configs` hold credentials in plain JSON; never log query results from these columns.
- Mixed-format and Hadoop-only columns in `table_metadata` (`change_location`, `primary_key`, `meta_store_site`, `hdfs_site`, `core_site`, Kerberos columns) are out of scope.
- `OptimizingProcessMapper` keeps `target_change_snapshot_id` and sequence columns from mixed-format support; Iceberg tables still write them.

## Tests

Mappers are exercised by service tests (for example `table/TestDefaultTableRuntimeStore`, `table/TestTableManagerQuery`, `catalog/TestIcebergOnlyCatalogService`):

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestTableManagerQuery -Dsurefire.failIfNoSpecifiedTests=false
```

They run on embedded Derby (`AMSManagerTestBase` → `DerbyPersistence`), so PostgreSQL-specific SQL and the migration scripts are not covered. `persistence/TestPostgresPersistence` is meant to cover PostgreSQL but its resource `PostgresPersistence` is still a stub.

## Related packages

- [`persistence`](../README.md), [`persistence/converter`](../converter/README.md), [`persistence/extension`](../extension/README.md).
- [`table`](../../table/README.md), [`catalog`](../../catalog/README.md).
