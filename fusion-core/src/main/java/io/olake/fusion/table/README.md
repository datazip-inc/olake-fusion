# `io.olake.fusion.table`

The table model shared by server and optimizer: table interfaces, identifiers, the catalog's Hadoop configuration holder (`TableMetaStore`), table property keys and defaults, and the table-runtime state API used by the server.

## Role in Fusion

An Iceberg table loaded by Fusion is seen by most code as a `MixedTable`. `IcebergTable.newIcebergTable` (in `io.olake.fusion.formats.iceberg`) wraps the Iceberg `Table` in a `BasicUnkeyedTable` (an anonymous subclass that also overrides `format()` to return `TableFormat.ICEBERG`). So for Fusion's scope, `MixedTable` = `UnkeyedTable` = `BasicUnkeyedTable` around an Iceberg table, and `format()` is always `ICEBERG`.

Runs in: **both**.
- Server: `TableProperties` (read by `TableConfigurations` to build `OptimizingConfig`), `TableRuntimeStore`/`StateKey`/`TableRuntimeFactory` (implemented in `fusion-ams` `io.olake.fusion.server.table`), `TableSummary` (health score, stored in Postgres), `TableMetaStore` (built per catalog by `utils.CatalogUtil.buildMetaStore`).
- Optimizer: the `BasicUnkeyedTable` arrives Java-serialized inside `RewriteFilesInput`. `TableMetaStore` is not sent to it.

Mixed-format only (out of scope): `PrimaryKeySpec` (still used for "no primary key" descriptions in `fusion-ams` `TableMetadata` and as an optional reader argument), the metadata columns `_file_offset`, `_change_action`, `_tree_node`, `WatermarkGenerator` (used by mixed commit code in `io.olake.fusion.op`), and the `mixed-format.*`, `change.*`, `log-store.*` property keys.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `MixedTable` | `Serializable` base interface: `id()`, `format()`, `schema()`, `name()`, `spec()`, `properties()`, `location()`, `io()` (an `AuthenticatedFileIO`), `refresh()`, `updateSchema()`, `updateProperties()`, `isUnkeyedTable()`/`asUnkeyedTable()`. | `io.AuthenticatedFileIO` |
| `UnkeyedTable` | `MixedTable` + Iceberg `Table`; adds `partitionProperty()` and `updatePartitionProperties(Transaction)`. | `op.PartitionPropertiesUpdate` |
| `BasicUnkeyedTable` | Delegates to the wrapped Iceberg `Table`; `format()` returns `ICEBERG`. Writes (`newAppend`, `newFastAppend`, `newRewrite`, `newRowDelta`, `newOverwrite`, `newDelete`, `newReplacePartitions`, `newTransaction`) go through `io.olake.fusion.op.Mixed*` builders. `properties()` merges catalog `table.*` defaults via `MixedFormatCatalogUtil.mergeCatalogPropertiesToTable`. | `op.MixedAppendFiles`, `op.MixedRewriteFiles`, `op.MixedTransaction`, `utils.MixedFormatCatalogUtil` |
| `TableIdentifier` | `catalog.database.tableName`; converts to and from the Thrift `io.olake.fusion.api.TableIdentifier`. | gen-java `api` |
| `TableMetaStore` | Plain holder of one Hadoop `Configuration`: the no-arg constructor builds a default one on first `getConfiguration()` (with a few IPC and Hive Metastore settings), the other constructor takes one. `doAs(Callable)` just runs the callable as the process user. Not `Serializable`. Iceberg catalog constructors and the terminal's Spark session take its configuration. | Hadoop `Configuration`, `formats.iceberg.IcebergCatalog`, `fusion-ams` terminal |
| `TableProperties` | Property keys and defaults (see Contracts). | `properties.CatalogMetaProperties` |
| `MetadataColumns` | Reserved field ids from `Integer.MAX_VALUE - 1001` down; `_transaction_id` (`TRANSACTION_ID_FILED`) is used by the Iceberg reader to carry the data sequence number. | `io.reader` |
| `TableSummary` | Per-table stats kept in the server's table runtime: file counts and sizes, `healthScore` (-1 = not computed), small-file/equality/positional-delete scores, pending files, `healthScoreSnapshotId`. | `fusion-ams` `TableHealthScoreExecutor`, `TableRuntimeMeta` |
| `TableRuntimeStore` | Server-side store of one table runtime: identifier, group, config, status code, summary, typed state; `begin()` returns a `TableRuntimeOperation` that batches updates and `commit()`s them. | `StateKey`, `fusion-ams` `DefaultTableRuntimeStore` |
| `StateKey<T>` | Named, typed runtime state with default value and string serializer (`longType()`, `stringType()`, `jsonType(Class)`, `propertiesType()`). | Jackson |
| `TableRuntimeFactory` | `ActivePlugin` that decides (`accept`) whether it can build a `TableRuntime` for a table and which `StateKey`s it needs. | `fusion-ams` `DefaultTableRuntimeFactory`, `TableRuntimeFactoryManager` |
| `TableSnapshot`, `BasicTableSnapshot` | Snapshot id holder used by the planner and `TableHealthScoreExecutor`. | — |
| `WatermarkGenerator` | Computes a watermark from file upper bounds of the event-time column. Mixed commit path only. | `op.MixedUpdate` |

## How it works

- **Loading.** `TableMetaStore` is built per catalog by `CatalogUtil.buildMetaStore(CatalogMeta)`, which always returns `new TableMetaStore()`. Its configuration is passed to Iceberg's `CatalogUtil.buildIcebergCatalog`. A loaded table's IO is the Iceberg table's own FileIO, wrapped by `io.AuthenticatedFileIOs.buildAdaptIcebergFileIO`.
- **Effective properties.** `BasicUnkeyedTable.properties()` returns the Iceberg table properties merged over the catalog's `table.*` properties. If `self-optimizing.enabled` resolves to false, catalog `self-optimizing.*` defaults are dropped and `self-optimizing.enabled=false` is kept. Table properties always win. OLake-UI sets the catalog default `table.self-optimizing.enabled=false` and per-table `self-optimizing.enabled`, the three `*.trigger.cron` keys and `self-optimizing.target-size` through `ALTER TABLE ... SET TBLPROPERTIES`.
- **Authentication.** None in `TableMetaStore`: `doAs` runs the callable directly as the process user, and there is no SIMPLE, Kerberos or AK/SK handling. Storage credentials come from the catalog's FileIO properties.
- **Runtime state.** `TableRuntimeStore.begin().updateState(key, fn)...commit()` applies updates under the store's lock; `getState(key)` deserializes the stored string with `StateKey.deserialize`.

## State, threads and concurrency

- `TableMetaStore.getConfiguration()` is `synchronized` and builds the configuration once per instance; there is no shared cache.
- `TableRuntimeStore.synchronizedInvoke(Runnable)` is the per-table lock in the server (`ReentrantLock` in `DefaultTableRuntimeStore`).
- `BasicUnkeyedTable` is as thread-safe as the wrapped Iceberg `Table`.

## Contracts

- **Table properties.** Keys Fusion and OLake-UI depend on: `self-optimizing.enabled` (code default `true`, OLake-UI catalog default `false`), `self-optimizing.minor.trigger.cron`, `self-optimizing.major.trigger.cron`, `self-optimizing.full.trigger.cron` (5-field Unix cron; empty/null = never), `self-optimizing.target-size` (default `134217728`, 128 MB; OLake-UI sends 512 MB), `self-optimizing.quota` (default `0.5`; OLake-UI catalog default `0.1`), `self-optimizing.group` (default `default`), `self-optimizing.max-task-size-bytes`, `self-optimizing.fragment-ratio`, `self-optimizing.min-target-size-ratio`, `self-optimizing.minor.trigger.file-count`, `self-optimizing.full.rewrite-all-files`, `self-optimizing.filter`. Renaming any key breaks OLake-UI's `terminal.go` and existing tables; check OLake-Helm and OLake-UI's `docker-compose-v1.yml` too.
- **`TableSummary` JSON.** Stored in Postgres column `table_runtime.table_summary` with `JsonObjectConverter` (Jackson, field names from getters). Renaming a getter changes the stored JSON; old rows then lose that field. Its `healthScore` is copied into the REST `tableSummary.healthScore` (see [`descriptor`](descriptor/README.md)), which OLake-UI reads.
- **Java serialization.** `MixedTable` implementations (`BasicUnkeyedTable`) and `TableIdentifier` are serialized into every task input and persisted in Postgres. `TableMetaStore` is not `Serializable` and is not part of task inputs.
- **StateKey values** are stored as strings in the table-state table; changing a key's name or serializer is a data migration.

## Extending or changing it

- New table property: add the key and default to `TableProperties`, read it in `fusion-ams` `TableConfigurations`, and check OLake-UI and OLake-Helm for places that set it.
- New runtime state: define a `StateKey` and list it in the `TableRuntimeCreator.requiredStateKeys()` of the factory.

## Invariants and pitfalls

- `TableFormat.ICEBERG` is the only format a `MixedTable` reports; code that still branches on other formats is dead for Fusion.
- `StateKey.deserialize` tests `StringUtils.isEmpty(key)` instead of the stored value, so a null stored value is passed to the deserializer (`Long.valueOf(null)` throws) instead of returning the default.
- `WatermarkGenerator.eventTimeFromTimestamp` returns the Iceberg timestamp value (microseconds) as milliseconds.
- `ENABLE_SELF_OPTIMIZING_DEFAULT` is `true`; a table in a catalog without OLake-UI's catalog default is enabled unless it says otherwise.

## Tests

`fusion-core/src/test/java/io/olake/fusion/utils/TestWatermarkGenerator.java`, `TestTablePropertyUtil.java`, `TestMixedFormatCatalogUtil.java`; runtime store behaviour is tested in `fusion-ams`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestMixedFormatCatalogUtil -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`descriptor`](descriptor/README.md), [`blocker`](blocker/README.md)
- [`../io`](../io/README.md), [`../utils`](../utils/README.md), [`../data`](../data/README.md)
- [`../../../../../gen-java/io/olake/fusion/api`](../../../../../gen-java/io/olake/fusion/api/README.md)
