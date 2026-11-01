# `io.olake.fusion.server.terminal`

Fusion's SQL terminal: runs a script of Spark SQL statements against one catalog in the
background and keeps its logs and results in memory for polling. OLake-UI uses it to write table
settings (`ALTER TABLE ... SET TBLPROPERTIES`).

## Role in Fusion

- **Callers.** `TerminalController` routes (see [`dashboard/controller`](../dashboard/controller/README.md)):
  `POST /api/ams/v1/terminal/catalogs/{catalog}/execute`, `GET /terminal/{sessionId}/logs`,
  `GET /terminal/{sessionId}/result`, `PUT /terminal/{sessionId}/stop`,
  `GET /terminal/latestInfos/`.
- **OLake-UI.** `PUT /api/opt/v1/{catalog}/{database}/tables/config` in OLake-UI's backend
  (`server/internal/services/optimization/terminal.go`) builds one
  `ALTER TABLE {db}.{table} SET TBLPROPERTIES (...)` per selected table (the
  `self-optimizing.enabled`, `self-optimizing.{minor,major,full}.trigger.cron` and
  `self-optimizing.target-size` keys), posts them joined by newlines to `execute`, then polls `logs`
  every second until `logStatus != "Running"` (5-minute timeout). It reports success when
  `logStatus == "Finished"` and returns `logs` to its frontend.
- **Backends.** `TerminalManager` loads a `TerminalSessionFactory` by `terminal.backend`:
  `local` (default, [`terminal/local`](local/README.md), an in-process Spark session), or `custom`
  with `terminal.factory`. Any other value fails startup (`FusionManagementConfValidator`).
- **Calls.** `CatalogManager.getCatalogMeta`, `CatalogUtil`, `AmsUtil.getAMSThriftAddress`,
  `StaticAwsCredentialsProvider.applyGlueCredentials` (Glue catalogs).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TerminalManager` | Entry point. `executeScript`, `getExecutionLog`, `getExecutionResults`, `cancelExecution`, `getLastSessionInfo`, `dispose`; owns the session map, the execution pool and the idle-session GC thread | `TerminalSessionContext`, `TerminalSessionFactory`, `CatalogManager` |
| `TerminalSessionContext` | One session id: its status, last `ExecutionTask`, lazily created `TerminalSession`. `submit` runs the script on the pool; `ExecutionTask.execute` splits it into statements and runs them | `ExecutionResult`, `TerminalSession`, `TableMetaStore.doAs` |
| `TerminalSessionFactory` | SPI: `initialize(Configurations)`, `create(TableMetaStore, Configurations)`. `SessionConfigOptions` defines the session keys (`session.catalogs`, `session.catalog.<c>.connector`, `catalog.<c>.<key>`, `session.fetch-size`, `catalog-url-base`, `using-session-catalog-for-hive`) | backends |
| `TerminalSession` | SPI for one live session: `executeStatement`, `logs`, `configs`, `active`, `release`; inner `ResultSet` | backends |
| `SparkContextUtil` | `getSparkConf`: `spark.sql.extensions` = Iceberg extensions and, per catalog, `spark.sql.catalog.<c>` = `org.apache.iceberg.spark.SparkCatalog` plus `spark.sql.catalog.<c>.<key>` for each catalog property. Rejects any connector other than `iceberg` | `SessionConfigOptions` |
| `ExecutionResult` | Synchronized lists of timestamped log lines and `StatementResult`s | |
| `StatementResult` | Statement text, line number, columns, rows, success flag | |
| `ExecutionStatus` | `Created`, `Running`, `Finished`, `Failed`, `Canceled`, `Expired` | |
| `SimpleResultSet` | `ResultSet` over a row list | `LocalTerminalSession` |
| `JDBCResultSet` | `ResultSet` over a JDBC `java.sql.ResultSet`; no backend in the repo uses it, available to `custom` backends | |

## How it works

`TerminalManager.executeScript(terminalId, catalog, script)`:

1. `terminalId` is the request's `JSESSIONID` cookie, or a new random UUID when there is none
   (`TerminalController.executeScript`).
2. Load `CatalogMeta`; build a `TableMetaStore` from its storage and auth config
   (`getCatalogTableMetaStore`).
3. Session id = `terminalId-<authMethod>-<user or principal>-<catalog>` with `/` replaced by `_`.
4. `catalogConnectorType` requires the catalog's table formats to be exactly `{ICEBERG}`, else
   `IllegalStateException("Terminal supports Iceberg catalogs only ...")`.
5. `applyClientProperties` sets `warehouse` to the catalog name for `ams` catalogs, otherwise adds
   `type` (when there is no `catalog-impl`), and, for Glue, static AWS credentials.
6. Build the session `Configurations`: fetch size `terminal.result.limit`, the catalog list,
   connector `iceberg`, `catalog-url-base`, and every catalog property as `catalog.<c>.<key>`.
7. Under `sessionMapLock`, reuse the existing context if its configuration is equal, else create a
   new `TerminalSessionContext`.
8. If the context is `Running`, throw. Else `submit`: status becomes `Running`; the task runs on
   `executionPool`; on completion the status becomes the task's result and `lastExecutionTime` is
   updated.

`ExecutionTask.get()` runs inside `metaStore.doAs`: `lazyLoadSession` (re-create if `active()` is
false), log every session config, then `execute`: read the script line by line, skip blank lines and
lines starting with `--` or `#`, end a statement at a line ending with `;`, run it with
`session.executeStatement(catalog, statement)`, fetch at most `fetchLimits` rows. A failed statement
logs the stack trace; with `terminal.stop-on-error: false` (default) the script continues and the
final status is still `Finished`. `cancel` sets a flag checked before each line.

`SessionCleanTask` (thread `terminal-session-gc`) wakes every 5 minutes and releases contexts that
are not `Running` and idle longer than `terminal.session.timeout` (default 30 min).

## State, threads and concurrency

- Sessions live only in memory (`sessionMap`); a server restart loses them and `logs` for an old id
  returns `Expired`.
- `executionPool` is `ThreadPoolExecutor(1, 50, 30 min, new LinkedBlockingQueue<>())`. With an
  unbounded queue the pool never grows past its core size, so **all scripts from all sessions run
  one at a time on one thread**. A slow statement (for example an `ALTER TABLE` against a slow REST
  catalog) delays every other terminal call, including OLake-UI's settings saves, which time out
  after 5 minutes.
- `executeScript` reads `sessionMap.get(sessionId)` after leaving the lock; the GC thread could
  remove it in between.
- `TerminalSessionContext.lazyLoadSession`, `submit`, `getLogs` and `getStatementResults` are
  `synchronized` on the context, so polling `logs` blocks while a new session is being created.
- `ExecutionResult.PATTEN` is a static `SimpleDateFormat` shared across contexts (not thread-safe;
  only affects log timestamps).
- `gcThread` is a non-daemon thread; `dispose()` (called by `FusionServiceContainer` on stop) sets
  `running = false`, interrupts it and shuts the pool down.

## Contracts

- Responses: `SessionInfo {sessionId}`, `LogInfo {logStatus, logs}` (`logStatus` is an
  `ExecutionStatus` name), `List<SqlResult {id, status, columns, rowData}>`. OLake-UI depends on
  `sessionId`, `logStatus` (`Running`, `Finished`) and `logs`.
- Config keys (`ams.terminal.*` in `config.yaml`): `terminal.backend`, `terminal.factory`,
  `terminal.result.limit` (1000), `terminal.stop-on-error` (false), `terminal.session.timeout`
  (30 min), `terminal.sensitive-conf-keys` (empty). Keys under `terminal.<backend>.` are passed to
  the factory with that prefix removed.
- `logStatus == "Finished"` does not mean every statement succeeded; OLake-UI's code has a TODO
  about this.

## Extending or changing it

- New backend: implement `TerminalSessionFactory` and `TerminalSession`, set
  `terminal.backend: custom` and `terminal.factory: <class>`. Use `SparkContextUtil.getSparkConf`
  for the catalog settings. Copy [`terminal/local`](local/README.md).
- Changing the status values or the `logs` shape changes OLake-UI's settings flow; update
  `terminal.go` together.

## Invariants and pitfalls

- **Secrets in logs.** `ExecutionTask.get` writes every `session.configs()` entry as
  `session configuration: key => value`, and the local factory also logs each Spark conf it sets.
  These include every catalog property (`catalog.<c>.<key>`, `spark.sql.catalog.<c>.<key>`), such
  as `s3.secret-access-key` for AK/SK catalogs or REST `credential`/`token`. Only keys listed in
  `terminal.sensitive-conf-keys` (exact full key, default empty, set in no config file in this repo)
  are masked. These logs are returned by `GET /terminal/{sessionId}/logs`, and OLake-UI passes them
  to its frontend.
- OLake-UI signs with an API token and sends no `JSESSIONID`, so every settings save gets a new
  random `terminalId`, a new session id, and a new session (a cloned Spark session in local mode).
  Contexts pile up until the 30-minute idle GC.
- `loadTerminalSessionFactory` strips `terminal.<backend>.` from every `terminal.*` key, also from
  keys that do not have that prefix, which yields mangled keys for those.
- A script line that holds two statements (`a; b;`) is run as one statement; splitting is per line.

## Tests

- `fusion-ams/src/test/java/io/olake/fusion/server/terminal/TestSparkContextUtil.java`
- Run it:
  `./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestSparkContextUtil -Dsurefire.failIfNoSpecifiedTests=false`
- For end-to-end behaviour, run the server from source, register the MinIO catalog, and call
  `execute` then `logs` with `curl` (see `CLAUDE.md`, section 5).

## Related packages

- [`terminal/local`](local/README.md)
- [`dashboard/controller`](../dashboard/controller/README.md) (`TerminalController`),
  [`dashboard/model`](../dashboard/model/README.md) (`LogInfo`, `SqlResult`, `SessionInfo`)
- [`catalog`](../catalog/README.md)
