# `io.olake.fusion.server.terminal.local`

The default terminal backend (`terminal.backend: local`): runs terminal SQL in a Spark session
inside the Fusion server JVM.

## Role in Fusion

- `TerminalManager.loadTerminalSessionFactory` instantiates `LocalSessionFactory` when
  `terminal.backend` is `local` (default; also set in `local-test/config.yaml` and
  `dist/src/main/fusion-bin/conf/config.yaml`).
- `TerminalSessionContext` calls `create` once per terminal session and then
  `LocalTerminalSession.executeStatement` per statement. This is the path OLake-UI's table-settings
  `ALTER TABLE` statements take.
- Not related to compaction: compaction runs on the Spark optimizer, not in this Spark session.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `LocalSessionFactory` | `initialize` keeps the factory config and `terminal.sensitive-conf-keys`; `lazyInitContext` creates one shared local `SparkSession`; `create` clones it per terminal session and applies the catalog Spark conf | `SparkContextUtil.getSparkConf`, `TableMetaStore`, `DesensitizationUtil` |
| `LocalTerminalSession` | `TerminalSession` over a cloned `SparkSession`: switches catalog with `use \`<catalog>\``, runs `session.sql(statement)`, collects rows into a `SimpleResultSet` | Spark SQL |

## How it works

1. **Shared context** (`lazyInitContext`, `synchronized`): `SparkConf` with app name
   `spark-local-context`, master `local[<cores>]` (`cores`, default 1, from `terminal.local.cores`),
   dynamic partition overwrite, `spark.executor.heartbeatInterval` 100s, `spark.network.timeout`
   200s, Iceberg SQL extensions, `spark.cleaner.referenceTracking` false, plus every factory key
   starting with `spark.` (from `terminal.local.spark.*`). Created once with `getOrCreate()`.
2. **Per session** (`create`): `context.cloneSession()`; for every catalog whose connector is
   in `EXTERNAL_CONNECTORS` (`iceberg`) or not `AMS`, copy each Hadoop config entry of the `TableMetaStore`
   (a default `Configuration`, the same for every catalog) to `spark.sql.catalog.<c>.hadoop.<key>`; set every key from `SparkContextUtil.getSparkConf`
   except the static `spark.sql.extensions`, logging `key  value` (masked if the key is in
   `terminal.sensitive-conf-keys`).
3. **Statements** (`executeStatement`): on the first statement or a catalog change, run
   `use \`<catalog>\`` (or `use \`spark_catalog\`` when `canUseSparkSessionCatalog` is true: the
   catalog property `type` is `hive` and `terminal.local.using-session-catalog-for-hive` is true);
   then `session.sql(statement).collectAsList()`.
4. `active()` runs `select 1`; `release()` does nothing (the shared context must stay alive).

## State, threads and concurrency

- One `SparkSession` context per server JVM, shared by all terminal sessions; each terminal session
  has its own cloned session (own conf and current catalog).
- `LocalTerminalSession.logs` is a plain list cleared on each `logs()` call; it is used only from
  the single terminal execution thread.
- Cloned sessions are never closed; they are dropped when `TerminalManager` removes the context.

## Contracts

- Config keys: `terminal.local.cores`, `terminal.local.spark.*`, and
  `terminal.local.using-session-catalog-for-hive` (read by `TerminalSession.canUseSparkSessionCatalog`).
- The catalog name in the URL is the Spark catalog name, so statements use `db.table`.

## Extending or changing it

To pass Spark settings, add `spark.*` keys under `ams.terminal.local` in `config.yaml`. They apply
only when the shared context is first created, so a restart is needed.

## Invariants and pitfalls

- The local Spark session runs in the server's heap. Large `SELECT`s are collected fully
  (`collectAsList`) before `terminal.result.limit` is applied in `TerminalSessionContext`.
- `updateSessionConf` logs every catalog secret in clear text unless its full key is in
  `terminal.sensitive-conf-keys` (default empty). See [`terminal`](../README.md).
- Keep `terminal.local.using-session-catalog-for-hive` false (as `local-test/config.yaml` and
  `dist/src/main/fusion-bin/conf/config.yaml` do). When true, statements for a Hive Metastore
  catalog run against `spark_catalog`, which `SparkContextUtil.getSparkConf` does not configure as
  an Iceberg catalog, instead of the Iceberg `SparkCatalog` set up for the catalog name.

## Tests

No tests target this package. `TestSparkContextUtil` covers the conf it consumes:
`./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestSparkContextUtil -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`terminal`](../README.md)
