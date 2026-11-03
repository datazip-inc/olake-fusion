# `io.olake.fusion.server.table`

Keeps one in-memory `TableRuntime` per managed Iceberg table, discovers tables from the registered catalogs, and persists each runtime's status, configuration and state to PostgreSQL.

## Role in Fusion

This package sits between the catalogs and everything that schedules work on a table.

- `FusionServiceContainer.startRestServices()` builds `DefaultTableManager` (REST-side queries, blockers, internal-table create/drop).
- `FusionServiceContainer.startOptimizingService()` is called from `transitionToLeader()`; Fusion runs a single server, so this happens once at startup (and again only if a start fails and is retried). It builds `TableRuntimeFactoryManager`, `DefaultTableService`, the optimizing service and the inline executors, adds each executor as a `RuntimeHandlerChain` (`addHandlerChain(...)`), calls `tableService.initialize()` and then `tableManager.setTableService(tableService)`. `disposeOptimizingService()` (from `transitionToFollower()` and `dispose()`) calls `tableService.dispose()`.
- Callers of the runtime: `optimizing/OptimizingQueue` (`beginPlanning`, `refresh`, `completeEmptyProcess`, `planFailed`, `recover`, `beginCommitting`), `scheduler/inline/TableRuntimeRefreshExecutor` (`refresh`, `markAsPending`, `recordSkippedOptimization`), `scheduler/inline/TableHealthScoreExecutor` (`setTableSummary`), `scheduler/PeriodicTableScheduler` (`getLastCleanTime`, `updateLastCleanTime`) and `DefaultOptimizingService` (casts `tableService.getRuntime(id)` to `DefaultTableRuntime`).
- What it calls: `catalog.CatalogManager` / `ServerCatalog` to list and load tables, the mappers in `persistence.mapper` (`TableRuntimeMapper`, `TableMetaMapper`, `TableProcessMapper`, `TableBlockerMapper`, `OptimizingProcessMapper`, `OptimizerMapper`), and `MetricManager` for per-table metrics.

The interfaces it implements live in `fusion-core`: `io.olake.fusion.TableRuntime`, `io.olake.fusion.table.TableRuntimeStore`, `io.olake.fusion.table.TableRuntimeFactory`, `io.olake.fusion.table.StateKey`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TableService` / `DefaultTableService` | Owns `tableRuntimeMap`; restores runtimes at startup; periodically syncs tables from every catalog; adds and disposes runtimes; forwards status/config changes to the handler chain. | `CatalogManager`, `TableRuntimeFactoryManager`, `RuntimeHandlerChain`, `TableRuntimeMapper`, `TableMetaMapper` |
| `TableManager` / `DefaultTableManager` | REST-facing facade: `listManagedTables`, `getServerTableIdentifier`, `getTableProcessMeta`, `getTableRuntimeMata`, `queryTableOptimizingInfo`, blockers (`block`, `renewBlocker`, `releaseBlocker`, `getBlockers`), internal-table `createTable` / `dropTableMetadata`. | `CatalogManager`, `TableService` (optional), all table mappers, `OptimizingUtil.buildTableOptimizeInfo` |
| `MaintainedTableManager` | Read-only part of `TableManager`. | — |
| `TableRuntimeHandler` | Two `handleTableChanged` overloads (status, config). `DefaultTableService` implements it and is set on each store. | `RuntimeHandlerChain` |
| `RuntimeHandlerChain` | Singly linked chain of listeners (`initHandler`, `handleTableAdded`, `handleTableRemoved`, `handleStatusChanged`, `handleConfigChanged`, `doDispose`). Exceptions in a handler are logged and swallowed (`doSilently`). | Inline executors, optimizing service, process service |
| `TableRuntimeFactoryManager` | Plugin manager for category `table-runtime-factories`. | `AbstractPluginManager`, `META-INF/services/io.olake.fusion.table.TableRuntimeFactory` |
| `DefaultTableRuntimeFactory` | Plugin `default`; accepts only `ICEBERG` identifiers and creates `DefaultTableRuntime` with `DefaultTableRuntime.REQUIRED_STATES`. | `DefaultTableRuntime` |
| `AbstractTableRuntime` | Base runtime; reads identifier, group, status code and parsed `TableConfiguration` from its store. | `TableRuntimeStore`, `TableConfigurations` |
| `DefaultTableRuntime` | The runtime used for every table: optimizing status transitions, cron bookkeeping (`markAsPending`, `recordSkippedOptimization`), process rows in `table_process`, quota accounting, snapshot refresh, cleanup timestamps, blocker check, process plugins (`install`, `trigger`). | `DefaultTableRuntimeStore`, `TableProcessMapper`, `OptimizingProcessMapper`, `TableBlockerMapper`, metrics classes |
| `DefaultTableRuntimeStore` | Persistent store behind one runtime: `table_runtime` row (`TableRuntimeMeta`) plus `table_runtime_state` rows; transactional `begin()...commit()` operations. | `TableRuntimeMapper`, `TableMetaMapper`, `NestedSqlSession` |
| `TableConfigurations` | Parses table properties into `TableConfiguration`, `OptimizingConfig`, `DataExpirationConfig`, `TagConfiguration`. | `TableProperties`, `CompatiblePropertyUtil` (fusion-core) |
| `TableMetadata` | Row of `table_metadata` for internal (`ams`) catalog tables. | `table.internal`, `TableMetaMapper` |
| `AbstractTableMetrics`, `TableOptimizingMetrics`, `TableOrphanFilesCleaningMetrics`, `TableSummaryMetrics` | Per-table gauges and counters tagged `catalog`, `database`, `table` (and `group` for optimizing metrics). | `MetricRegistry` |

Sub-packages: [`blocker`](blocker/README.md), [`cleanup`](cleanup/README.md), [`internal`](internal/README.md).

## How it works

### Startup: restoring runtimes (`DefaultTableService.initialize`)

1. Reads all rows with `TableRuntimeMapper.selectAllRuntimes`, all identifiers with `TableMetaMapper.selectAllTableIdentifiers`, and all states with `TableRuntimeMapper.selectAllStates`.
2. For each `TableRuntimeMeta` with a matching identifier, `createTableRuntime(...)` asks each installed factory `accept(identifier, tableConfig)`; the first match builds a `DefaultTableRuntimeStore(identifier, meta, creator.requiredStateKeys(), states)`, sets `this` as its runtime handler, and calls `creator.create(store)`. Rows with no identifier or no factory are logged and skipped.
3. Registers metrics, fills `tableRuntimeMap`, then calls `headHandler.initialize(tableRuntimes)` so each handler gets the restored runtimes before anything else fires.
4. Creates the `table-explorer-executor-%d` pool and schedules `exploreTableRuntimes` on `table-explorer-scheduler-%d` at `refresh-external-catalogs.interval`.

`addHandlerChain` and `initialize` both call `checkNotStarted()`; handlers can only be added before `initialize`. `getRuntime`, `contains` and `setRuntime` block on `checkStarted()` until initialization completes.

### Table discovery (`exploreTableRuntimes`)

For each catalog from `catalogManager.getServerCatalogs()`:

- **External catalogs** (`exploreExternalCatalog`): `listDatabases()` then one async `listTables(database)` per database on the explorer pool. Results are compared by database and table name (`TableIdentity.equals` ignores format) against `TableMetaMapper.selectTableIdentifiersByCatalog`.
  - New in the catalog → `syncTable`: in one `doAsTransaction`, `ExternalCatalog.syncTable` inserts the `table_identifier` row, then `triggerTableAdded` creates the runtime. If anything throws after the runtime was added to the map, `revertTableRuntimeAdded` removes it from the map.
  - Gone from the catalog → `disposeTable`, except for tables in a database whose `listTables` call failed in this cycle (`failedDatabases`). A failed listing never drops tables.
- **Internal catalogs** (`exploreInternalCatalog`): compare `table_identifier` rows with `tableRuntimeMap`; add missing runtimes, dispose runtimes whose row is gone.

Afterwards, every runtime whose catalog name is no longer in `catalogManager.listCatalogMetas()` is disposed. This is how runtimes of a dropped catalog are cleaned up.

### Adding a runtime (`triggerTableAdded`)

1. `catalog.loadTable(db, table)`; Iceberg tables for which `TablePropertyUtil.isMixedTableStore(properties)` is true are skipped (`triggerTableAdded` returns `false`).
2. Builds a `TableRuntimeMeta`: `tableConfig` = table properties, `statusCode` = `OptimizingStatus.IDLE` (700), `groupName` = `self-optimizing.group` from `TableConfigurations.parseTableConfig`, empty `TableSummary`. Inserts it with `TableRuntimeMapper.insertRuntime`.
3. `createTableRuntime(identifier, meta, emptyList)`. The store's `restoreStates` inserts a default row for each required state key (`saveState`) and reads it back (`getState`).
4. Puts the runtime in the map, registers metrics, and calls `headHandler.fireTableAdded(table, runtime)`.

`onTableCreated` (from `DefaultTableManager.createTable` on internal catalogs) also ends in `triggerTableAdded`.

### Disposing a runtime (`disposeTable`)

In one `doAsTransaction`: `headHandler.fireTableRemoved(runtime)`, `runtime.dispose()` (closes any running `OptimizingProcess` with `close(false)`, unregisters metrics, and `DefaultTableRuntimeStore.dispose()` deletes from `table_runtime`, `table_runtime_state` and `table_identifier`), map removal, then `TableMetaMapper.deleteTableIdByName`. If the transaction fails, the runtime is put back in the map. `table_process` history rows are not deleted here.

### Runtime state and the store

`DefaultTableRuntime.REQUIRED_STATES` defines four `table_runtime_state` keys:

| State key | Type | Holds |
| --- | --- | --- |
| `optimizing_state` | JSON `TableRuntimeOptimizingState` | current/last-optimized snapshot ids, last Minor/Major/Full times, `lastOptimizingType` |
| `pending_input` | JSON `AbstractOptimizingEvaluator.PendingInput` | last evaluated pending input |
| `process_id` | long | id of the current `table_process` row, `0` when none |
| `cleanup_state` | JSON `cleanup.TableRuntimeCleanupState` | last cleanup times per `CleanupOperation` |

On restore, extra state rows not in the required list are deleted (`removeState`) with a warning.

All writes go through `store().begin()`, which returns a `TableRuntimeOperationImpl`:

- The constructor copies `meta` and all state values.
- `updateState`, `updateGroup`, `updateStatusCode`, `updateTableConfig`, `updateTableSummary` queue functions over that copy. `updateStatusCode` and `updateTableConfig` also queue a handler callback with the value from before the change.
- `commit()` opens a `NestedSqlSession`, takes `tableLock`, runs the functions, and `persist()`s: `updateRuntime` (whole row) when group/status/config changed, otherwise `updateTableSummary` (summary column only); then `setStateValue` per touched state key. On success it copies the new values into the live `meta` and states (`visitable()`) and fires the queued callbacks into `DefaultTableService.handleTableChanged`, which forwards to `fireStatusChanged` / `fireConfigChanged`.

### Status lifecycle (`DefaultTableRuntime`)

Statuses are `OptimizingStatus` codes (`IDLE` 700, `PENDING` 600, `PLANNING` 500, `COMMITTING` 400, `MINOR_OPTIMIZING` 300, `MAJOR_OPTIMIZING` 200, `FULL_OPTIMIZING` 100).

| Method | Caller | Effect |
| --- | --- | --- |
| `markAsPending(cronType)` | `TableRuntimeRefreshExecutor` when a cron fires | Inserts a `table_process` row (`PENDING`, `process_type` = Minor/Major/Full name, `execution_engine` `AMORO`, summary `optimizingType`), then IDLE → PENDING and sets `process_id`. |
| `recordSkippedOptimization(type, reason)` | `TableRuntimeRefreshExecutor` | Inserts and updates a `SKIPPED` `table_process` row in one `doAs` (one transaction) with `skipReason`. |
| `beginPlanning()` | `OptimizingQueue` | Updates the process row to `PLANNING`; status → PLANNING. |
| `planFailed(reason, t)` | `OptimizingQueue` | Process row → `FAILED` (and a JSON line in `driver.log`); status → IDLE, `process_id` → 0. |
| `completeEmptyProcess(targetSnapshotId, targetChangeSnapshotId)` | `OptimizingQueue` when the planner finds nothing | Process row → `SKIPPED`; status → IDLE; `lastOptimizedSnapshotId` → the planner's target snapshot; resets `pending_input` and `process_id`. |
| `beginProcess(process)` | `OptimizingQueue` (inside `invokeConsistency`) | `process_id` = process id; status → `OptimizingStatus.ofOptimizingType(...)`; resets `pending_input`. |
| `beginCommitting()` | `OptimizingQueue` | status → COMMITTING. |
| `completeProcess(success)` | `OptimizingQueue` and `recover` | On success only, advances `lastOptimizedSnapshotId`, `lastOptimizedChangeSnapshotId`, `lastOptimizingType`; always sets the per-type last time to the plan time; status → IDLE. |
| `recover(process)` | `OptimizingQueue` at startup | Re-attaches a running process; requires a processing status and the same `process_id`. |
| `refresh(table)` | `TableRuntimeRefreshExecutor`, `OptimizingQueue` | Replaces `table_config` with current table properties, updates the group (closing the running process if the group changed), and refreshes current snapshot ids when `self-optimizing.enabled` is true. |
| `setTableSummary(summary, snapshotId)` | `TableHealthScoreExecutor` | Writes health scores and file counts to `table_summary` only, and refreshes `TableSummaryMetrics`. |
| `setPendingInput`, `optimizingNotNecessary` | no caller in `fusion-ams/src/main` | Update `pending_input` (and IDLE → PENDING), or mark the current snapshot as optimized while IDLE. |

`appendDriverLogEntry` writes to `$LOG_DIR/<processId>/driver.log`, defaulting to `/mnt/amoro-logs/compaction`.

Quota: `addTaskQuota` (called by `OptimizingQueue`) inserts into `optimizing_task_quota` (`doAsIgnoreError`) and keeps an in-memory window of `FusionServiceConstants.QUOTA_LOOK_BACK_TIME`; `calculateQuotaOccupy()` divides used time by `self-optimizing.quota` (a fraction is multiplied by the optimizer thread count from `OptimizerMapper.selectAll`).

### Configuration parsing (`TableConfigurations.parseTableConfig`)

Called with the table's properties, which for Iceberg tables already include catalog properties prefixed `table.` (merged in `BasicUnkeyedTable.properties()`; table values win). Called on every `getTableConfiguration()`; nothing is cached. Keys read (constants in `io.olake.fusion.table.TableProperties`):

| Property | Default | Used for |
| --- | --- | --- |
| `self-optimizing.enabled` | `true` (OLake-UI's catalog sets `table.self-optimizing.enabled=false`) | `OptimizingConfig.isEnabled` |
| `self-optimizing.group` | `default` (catalogs created via `CatalogController` set `table.self-optimizing.group`) | runtime group, `table_runtime.group_name` |
| `self-optimizing.target-size` | `134217728` (128 MB) | target file size |
| `self-optimizing.quota` | `0.5` | quota |
| `self-optimizing.minor.trigger.cron`, `.major.trigger.cron`, `.full.trigger.cron` | `null` | Lite/Medium/Full crons |
| `self-optimizing.allow-partial-commit`, `self-optimizing.execute.num-retries`, `self-optimizing.filter`, `self-optimizing.min-plan-interval` and the other `SELF_OPTIMIZING_*` keys | see `TableProperties` | planner tuning |
| `gc.enabled` | `true` | gates the four cleanups below |
| `table-expire.enabled`, `snapshot.keep.duration` (`720min`), `snapshot.keep.min-count` (`1`) | `false` / … | snapshot expiry |
| `clean-orphan-file.enabled` (`false`), `clean-orphan-file.min-existing-time-minutes` (`2880`) | | orphan files |
| `clean-dangling-delete-files.enabled` | `true` | dangling deletes |
| `data-expire.enabled` (`false`) and `DATA_EXPIRATION_*` keys | | data expiry |
| `tag.auto-create.enabled` and `AUTO_CREATE_TAG_*` keys | | tags |

`parseTagConfiguration` throws `IllegalArgumentException` for a trigger period other than daily/hourly, and `parseDataExpirationBaseOnRule` for an unknown rule: a bad value fails parsing for the whole table.

### Blockers and queries (`DefaultTableManager`)

`block(...)` retries up to `TABLE_BLOCKER_RETRY` (3) times: delete expired blockers, read live ones, fail with `BlockerConflictException` on conflict, then insert a `TableBlocker` whose `prevBlockerId` is the current max id. The unique index `uq_prev` on `table_blocker (catalog_name, db_name, table_name, prev_blocker_id)` makes two concurrent inserts with the same predecessor collide; the loser gets a `PersistenceException` and retries. Timeout is `blocker.timeout` (default 1 min). `DefaultTableRuntime.isBlocked(operation)` checks live blockers but has no caller in `fusion-ams/src/main`. See [`blocker`](blocker/README.md).

`queryTableOptimizingInfo` pages `TableRuntimeMapper.queryForGroups` with PageHelper (`PageHelper.startPage(offset / limit + 1, limit, true)`), then joins identifiers, latest process ids (`selectTableMaxProcessIds`), task metas, quotas and optimizer thread counts.

## State, threads and concurrency

- `tableRuntimeMap` is a `ConcurrentHashMap<Long, TableRuntime>` keyed by `table_identifier.table_id`.
- `table-explorer-scheduler-%d` is single-threaded with `scheduleAtFixedRate`, so explore cycles never overlap. Listing and sync/dispose tasks run on `table-explorer-executor-%d` (`refresh-external-catalogs.thread-count` threads, queue `refresh-external-catalogs.queue-size`); a full queue is logged and the work is skipped until the next cycle.
- `DefaultTableRuntimeStore.tableLock` serializes `commit()` and `synchronizedInvoke`. Handler callbacks run after the lock is released.
- `DefaultTableRuntime` keeps `optimizingProcess`, `lastPlanTime` and `pendingCronType` in volatile fields; `taskQuotas` is a `CopyOnWriteArrayList`; `TableProcessContainer.trigger` uses its own `ReentrantLock`.
- All DB sessions are thread-bound (`NestedSqlSession` uses a `ThreadLocal`), so work on the explorer pool never joins a transaction opened on another thread.
- `DefaultTableService` exists only between `startOptimizingService()` and `disposeOptimizingService()`; `DefaultTableManager` is built earlier and treats its `TableService` as optional.

## Contracts

PostgreSQL tables (`fusion-ams/src/main/resources/postgres/ams-postgres-init.sql`):

| Table | Read | Written |
| --- | --- | --- |
| `table_runtime` (`table_id`, `group_name`, `status_code` default 700, `status_code_update_time`, `table_config`, `table_summary`, `bucket_id`) | `selectAllRuntimes`, `selectRuntime`, `queryForGroups` | `insertRuntime`, `updateRuntime`, `updateTableSummary`, `deleteRuntime` |
| `table_runtime_state` (unique `(table_id, state_key)`) | `selectAllStates`, `getState` | `saveState`, `setStateValue` (increments `state_version`), `removeState`, `removeAllTableStates` |
| `table_identifier` (unique `(catalog_name, db_name, table_name)`) | `selectAllTableIdentifiers`, `selectTableIdentifiersByCatalog`, `selectTableIdentifier`, `selectTableIdentifiers` | `ExternalCatalog.syncTable` → `insertTable`; `deleteTableIdByName`, `deleteTableIdById` |
| `table_process` | `getProcessMeta`, `selectTableMaxProcessIds` | `insertProcess`, `updateProcess` |
| `table_blocker` | `selectBlockers`, `selectBlocker` | `insert`, `renewBlocker`, `deleteBlocker`, `deleteExpiredBlockers` |
| `optimizing_task_quota` | `selectTaskQuotasByTime`, `selectTableQuotas` | `insertTaskQuota` |

Server config keys (`FusionManagementConf`): `refresh-external-catalogs.interval` (1 min), `refresh-external-catalogs.thread-count` (10), `refresh-external-catalogs.queue-size` (1000000), `blocker.timeout` (1 min). Plugin config: `table-runtime-factories` in `dist/src/main/fusion-bin/conf/config.yaml` and `conf/plugins/table-runtime-factories.yaml`.

OLake-UI reads this package's output through the table-details and `optimizing-processes` endpoints; the `table_process` rows written by `markAsPending` and `recordSkippedOptimization` (`status`, `process_type`, `summary.optimizingType`, `summary.skipReason`) are what OLake-UI shows as run history.

## Extending or changing it

- **New runtime state**: add a `StateKey` in `DefaultTableRuntime` (copy `CLEANUP_STATE_KEY`) and append it to `REQUIRED_STATES`. Existing tables get the default row on the next restore; no migration is needed. Removing a key deletes its rows on the next restart.
- **New table property**: add the key and default to `fusion-core` `TableProperties`, a field to the relevant config class, and one `CompatiblePropertyUtil.propertyAs*` call in `TableConfigurations` (copy `setMinorTriggerCron`). If OLake-UI should set it, it must be added to OLake-UI's table-config request too.
- **New listener**: subclass `RuntimeHandlerChain` and register it in `FusionServiceContainer.startOptimizingService()` before `tableService.initialize()`.
- **New status transition**: write it as one `store().begin()...commit()` chain so the row and states change together and handlers see one event.

## Invariants and pitfalls

- A failed `listTables` for a database must never dispose that database's tables; keep `failedDatabases`.
- Changing a catalog's `database-filter` or `table-filter` makes excluded tables look dropped: their runtime, state and `table_identifier` rows are deleted on the next cycle.
- `completeProcess(false)` must not advance `lastOptimizedSnapshotId`; the next cron tick relies on the unchanged checkpoint to reschedule.
- `markAsPending` writes the `table_process` row and the runtime row in two separate transactions; a failure between them leaves a `PENDING` row with no runtime pointing at it.
- `TableRuntimeOperationImpl` copies `meta` in `begin()`, outside `tableLock`. A meta-changing operation that commits after another thread's commit writes its own snapshot back with `updateRuntime`. Keep `begin()` and `commit()` adjacent; use `updateTableSummary` alone when only the summary changes.
- When `commit()` runs inside an outer transaction, handler callbacks and in-memory updates happen before the outer commit; an outer rollback leaves memory ahead of the database.
- `syncTable` fires `fireTableAdded` inside the transaction; if the transaction then fails, handlers have already seen the table.
- `RuntimeHandlerChain` swallows handler exceptions; a broken handler only logs.
- `TableRuntimeMeta.setStatusCode` always refreshes `statusCodeUpdateTime`, even when the code is unchanged.
- `TableOptimizingMetrics.statusChanged` is never called in `fusion-ams/src/main`, so the `table_optimizing_status_*` gauges keep the status set at construction (`IDLE`).
- `DefaultTableRuntimeStore.getState` uses `Preconditions.checkNotNull` on a boolean, which never fails; a missing key throws `NullPointerException` instead.
- `DefaultTableRuntime.refresh` casts the loaded table to `MixedTable` and then to `UnkeyedTable` to read snapshots; only unkeyed Iceberg tables reach it.

## Tests

Tests are in `fusion-ams/src/test/java/io/olake/fusion/server/table/` (`TestDefaultTableRuntimeStore`, `TestDefaultTableRuntimeHandler`, `TestDefaultTableRuntimeManager`, `TestSyncTableOfExternalCatalog`, `TestTableManagerQuery`, `TestTableSummaryMetrics`, `TestCatalogService`, `TestDatabaseService`). Run one with:

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestDefaultTableRuntimeStore -Dsurefire.failIfNoSpecifiedTests=false
```

These tests use embedded Derby through `DerbyPersistence` (`AMSManagerTestBase`), not PostgreSQL. `SchemaMigrator` skips non-Postgres databases, so migrations and Postgres-only SQL are not covered.

## Related packages

- [`catalog`](../catalog/README.md): catalogs this package lists and loads tables from.
- [`persistence`](../persistence/README.md) and [`persistence/mapper`](../persistence/mapper/README.md): sessions and SQL.
- [`blocker`](blocker/README.md), [`cleanup`](cleanup/README.md), [`internal`](internal/README.md).
- `../optimizing`, `../scheduler`: main callers of `DefaultTableRuntime`.
