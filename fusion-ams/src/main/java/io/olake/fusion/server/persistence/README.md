# `io.olake.fusion.server.persistence`

Fusion's database layer: builds the PostgreSQL connection pool, creates and migrates the schema at startup, sets up MyBatis, and gives every server component the `PersistentBase` helpers that open, nest, commit and roll back sessions.

## Role in Fusion

- `FusionServiceContainer` (config loading) calls `DataSourceFactory.createDataSource(serviceConfig)` and then `SqlSessionFactoryProvider.getInstance().init(dataSource)`, before any manager is built. `createDataSource` runs the init script and `SchemaMigrator.migrate`.
- Almost every stateful server class extends `PersistentBase` (for example `DefaultCatalogManager`, `DefaultTableService`, `DefaultTableManager`, `DefaultTableRuntime`, `DefaultTableRuntimeStore`, `ServerCatalog`). `DefaultOptimizingService` and `optimizing.TaskRuntime` extend `StatedPersistentBase`.
- `FusionServiceContainer` uses `HttpSessionHandlerFactory.createSessionHandler` for the HTTP server, so Jetty sessions live in `http_session`.
- `PlatformPropertyStore` is used by `utils/Telemetry` and `dashboard/controller/TelemetryController`; `TaskFilesPersistence` by `optimizing/OptimizingQueue`.
- The SQL itself is in [`mapper`](mapper/README.md); type conversion in [`converter`](converter/README.md); the IN-list helper in [`extension`](extension/README.md).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `DataSourceFactory` | Builds a commons-dbcp2 `BasicDataSource` (auto-commit off, pool sizes from config); `createTablesIfNeed` runs `postgres/ams-postgres-init.sql` when `catalog_metadata` is missing; then `SchemaMigrator.migrate`. | `FusionManagementConf`, MyBatis `ScriptRunner` |
| `SchemaMigrator` | Applies `postgres/migration/V<n>__<name>.sql` scripts listed in `MIGRATIONS`, recording each in `ams_schema_migration` with a SHA-256 checksum. | JDBC `Connection` |
| `SqlSessionFactoryProvider` | Singleton; registers every mapper, the PageHelper `PageInterceptor` (`reasonable=false`), dialect aliases, and the database id (`postgres`, `mysql`, `derby`). | MyBatis, PageHelper |
| `PersistentBase` | `doAs`, `getAs`, `updateAs`, `doAsExisted`, `doAsIgnoreError`, `doAsTransaction`, `beginSession`. | `NestedSqlSession` |
| `NestedSqlSession` | Thread-local session that nests up to `MAX_NEST_BEGIN_COUNT` (5) levels; only the outermost level commits or closes. | MyBatis `SqlSession` |
| `StatedPersistentBase` | `invokeConsistency(...)`: snapshots fields annotated `@StateField` (fusion-core `io.olake.fusion.StateField`) and restores them if the call throws. | `PersistentBase` |
| `TableRuntimeMeta` | Row of `table_runtime`. `copy()`; `setStatusCode` also sets `statusCodeUpdateTime` to now. | `TableRuntimeMapper`, `DefaultTableRuntimeStore` |
| `TableRuntimeState` | Row of `table_runtime_state`. | `TableRuntimeMapper` |
| `OptimizingProcessState` | Row of `optimizing_process_state` (snapshot targets, from/to sequences). | `OptimizingProcessMapper.getProcessState` |
| `TaskFilesPersistence` | Stores / loads gzip-serialized `RewriteFilesInput` maps in `optimizing_process_state.rewrite_input`; deserializes task outputs. | `OptimizingProcessMapper`, `SerializationUtil`, `CompressUtil` |
| `PlatformPropertyStore` | Key-value access to `platform_property` (`get`, `put`); key `TELEMETRY_INSTALL_ID = "telemetry.install_id"`. | `PlatformPropertyMapper` |
| `HttpSessionHandlerFactory` | Jetty `SessionHandler` backed by `JDBCSessionDataStore` on `http_session`; timeout `HTTP_SERVER_SESSION_TIMEOUT`. `prepareTables()` does not create tables. | Jetty |

## How it works

### Session handling

`beginSession()` calls `NestedSqlSession.openSession(...)` with a supplier that opens a MyBatis session at `READ_COMMITTED`.

- If the current thread has no session, a new one is opened and stored in the `ThreadLocal`.
- If it has one, `openNestedSession()` increments `nestCount` and returns the same object.
- `commit()` only commits when `nestCount == 0`; inner commits are no-ops.
- `rollback()` sets `isRollingback`; at the outer level it rolls back. Any later `commit()` or nested open on the same session fails with "session is rolling back".
- `close()` decrements `nestCount`, or at the outer level closes the session and removes it from the `ThreadLocal`.

The `PersistentBase` helpers:

| Helper | Behaviour |
| --- | --- |
| `doAs(Mapper.class, consumer)` | Run, commit; on any throwable roll back and rethrow wrapped as `PersistenceException` (`FusionRuntimeException.wrap`). |
| `getAs(Mapper.class, func)` | Run and return; no commit; wraps errors. |
| `updateAs(Mapper.class, func)` | Like `doAs`, returns the affected-row count as `Long`. |
| `doAsExisted(Mapper.class, func, errorSupplier)` | Like `doAs`, but throws `errorSupplier.get()` (and rolls back) when the function returns 0 rows. |
| `doAsIgnoreError(Mapper.class, consumer)` | Run and commit; logs and swallows errors without rolling back. |
| `doAsTransaction(Runnable...)` | Opens one session, runs each runnable (each typically calling `doAs`, which nests), commits once; on failure logs, rolls back and rethrows. |

So a sequence of `doAs` calls inside `doAsTransaction` is one database transaction. Example: `InternalCatalog.createTable` inserts into `table_identifier` and `table_metadata` and increments two counters atomically.

### Schema creation and migration

1. `createTablesIfNeed` (only when `database.auto-create-tables` is true): for `postgres` it checks `information_schema.tables` for `catalog_metadata` in `current_schema()`; if absent it runs `postgres/ams-postgres-init.sql` with `ScriptRunner`. The script path is resolved with `ClassLoader.getSystemResource`.
2. `SchemaMigrator.migrate` (only when `database.auto-create-tables` is true and `database.type` is `postgres`):
   - `declaredMigrations()` parses the version from each name in `MIGRATIONS` (`V<n>__...`) and fails on a duplicate version.
   - `readHistory` runs `postgres/migration/migration-history.sql` (`CREATE TABLE IF NOT EXISTS ams_schema_migration`) and reads `version, script_name, checksum`. If the create fails (two instances racing), it rolls back and reads anyway.
   - For each declared version not yet recorded, `apply` inserts the history row and executes the whole script as one statement, then commits. If it fails and `isAlreadyApplied` finds the row, another instance applied it and startup continues; otherwise it throws `IllegalStateException` and the server does not start.
   - For recorded versions, `verifyUnchanged` fails startup if the script name differs or the checksum (SHA-256 of the script with `\r\n` → `\n`) changed.

Current migrations: `V1__platform_property.sql` (creates `platform_property`). The init script does not create `platform_property` or `ams_schema_migration`; those come only from the migration path.

The files `postgres/upgrade*.sql` are upstream Amoro upgrade notes; no code runs them.

## State, threads and concurrency

- One session per thread; work handed to another thread (for example `CompletableFuture` tasks in `DefaultTableService`) runs in its own session and is not part of the caller's transaction.
- `SqlSessionFactoryProvider.sqlSessionFactory` is set once (double-checked locking); a second `init` builds a configuration but keeps the first factory.
- `StatedPersistentBase` caches the `@StateField` fields per class in a static `ConcurrentHashMap`.
- Pool: `database.connection-pool-max-total` (20), `-max-idle` (16), `-max-wait-millis` (30000); abandoned connections removed after 60 s.

## Contracts

- Config keys (`FusionManagementConf`): `database.type` (default `postgres`), `database.url`, `database.jdbc-driver-class` (`org.postgresql.Driver`), `database.username`, `database.password` (both set only for `mysql`/`postgres`), `database.auto-create-tables` (`true`), the three pool keys above.
- Tables owned here: `ams_schema_migration` (`version` PK, `script_name`, `checksum`, `applied_at`), `platform_property` (`property_key` PK, `property_value`, `create_time`), `http_session`.
- Migration rules (from the `SchemaMigrator` Javadoc): idempotent DDL (`IF NOT EXISTS`); never edit a shipped script; no down migrations; no `COMMIT`/`ROLLBACK` and nothing Postgres forbids inside a transaction (such as `CREATE INDEX CONCURRENTLY`).

## Extending or changing it

- **Add a migration**: create `fusion-ams/src/main/resources/postgres/migration/V2__<name>.sql` (copy `V1__platform_property.sql`) using `IF NOT EXISTS` forms, and append `"V2__<name>.sql"` to `SchemaMigrator.MIGRATIONS`. Do not put DDL in Java. Decide whether `ams-postgres-init.sql` should also change; a fresh database runs both.
- **Add a mapper**: write the interface in `mapper`, then add `configuration.addMapper(NewMapper.class)` in `SqlSessionFactoryProvider.init`, or MyBatis will not find it.
- **Multi-statement writes**: wrap them in `doAsTransaction` rather than calling `doAs` in sequence.

## Invariants and pitfalls

- Never edit a shipped migration; startup fails on checksum mismatch.
- `doAsIgnoreError` does not roll back. Inside an outer transaction on PostgreSQL, a failed statement aborts the whole transaction, so the outer commit will fail even though the error was "ignored".
- An inner `doAs` failure that the outer code catches still marks the shared session as rolling back; the outer `commit()` then throws.
- `MAX_NEST_BEGIN_COUNT` is 5; deeper nesting throws `IllegalStateException`.
- `database.password` and `admin-password` are listed under `sensitive-keywords` in `local-test/config.yaml`; config values set from the environment are not logged (`initEnvConfig` logs keys only). Keep it that way.
- Derby/MySQL branches remain in the code (`DB_TYPE_DERBY`, `DB_TYPE_MYSQL`, their init scripts, dialect aliases); Fusion runs on PostgreSQL only.
- `ams-postgres-init.sql` still creates `ha_lease`; no mapper reads or writes it.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/persistence/`: `TestNestedSqlSession`, `TestPersistentBase`, `TestStatedPersistentBase`, `TestDataSourceFactory`, `TestPostgresPersistence`, `PostgresPersistence`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestNestedSqlSession -Dsurefire.failIfNoSpecifiedTests=false
```

Current state (mid-refactor): service tests still run on embedded Derby through `table/DerbyPersistence` (`AMSManagerTestBase`), and `SchemaMigrator` skips Derby, so migrations and Postgres-only SQL are untested. `PostgresPersistence` is a stub whose methods throw `UnsupportedOperationException`, so `TestPostgresPersistence` cannot pass. `TestDataSourceFactory` expects `createDataSource` to reject `derby` and `mysql` with an `IllegalArgumentException` naming `postgres`; `DataSourceFactory` does not do that yet.

## Related packages

- [`persistence/mapper`](mapper/README.md), [`persistence/converter`](converter/README.md), [`persistence/extension`](extension/README.md).
- [`table`](../table/README.md): `DefaultTableRuntimeStore` is the heaviest user of nested sessions.
- [`catalog`](../catalog/README.md).
