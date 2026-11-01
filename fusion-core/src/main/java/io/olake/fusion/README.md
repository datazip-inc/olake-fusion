# `io.olake.fusion`

The root package of `fusion-core`: the format-neutral catalog and table abstractions, the table
format registry, the server's table-runtime and process-plugin interfaces, error codes, and the
string constants that the server and the optimizer exchange.

## Role in Fusion

The package is in `fusion-core`, so it is on the classpath of both the server (`fusion-ams`) and
the optimizer (`fusion-optimizer/*`). What actually runs where:

- **Both, as a wire contract:** `ErrorCodes` (the server puts these codes into thrift
  `AmoroException`s; the optimizer reads them to decide whether to retry), `OptimizerProperties`
  (keys the server and the optimizer use for optimizer and resource-group properties, including the
  `optimizer-tag` handshake), `Constants` (thrift service names), and `AmsClient`.
- **Server:** the catalog abstraction (`UnifiedCatalog`, `CommonUnifiedCatalog`,
  `FormatCatalog`, `FormatCatalogFactory`, `FusionCatalog`, `FusionTable`, `TableSnapshot`),
  `TableRuntime`, `SupportsProcessPlugins`, `Action` / `IcebergActions`, `ServerTableIdentifier`,
  `StateField`, `TableIDWithFormat`.
- **Shared types used on both sides:** `TableFormat`, `IcebergFileEntry`, `FusionIOException`,
  the plugin interfaces `FusionPlugin` and `ActivePlugin`.

Fusion works on Iceberg only. `TableFormat.ICEBERG` is the only public format. The name
`MIXED_ICEBERG` is also registered, privately, as a legacy marker, so that stored catalog and table
rows that carry it still parse and can then be rejected. `Constants.INNER_TABLE_BASE` /
`INNER_TABLE_CHANGE` are mixed-format store names, still referenced by `data.FileNameRules`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `FusionCatalog` | Base catalog interface: `listDatabases`, `databaseExists`, `tableExists`, `createDatabase`, `dropDatabase`, `loadTable`, `dropTable`. | `FusionTable`, `NoSuchTableException`, `NoSuchDatabaseException`, `AlreadyExistsException` |
| `FormatCatalog` | A `FusionCatalog` for one table format; adds `listTables(database)`. | `formats.iceberg.IcebergCatalog` (the only implementation in the tree) |
| `FormatCatalogFactory` | SPI that builds a `FormatCatalog` (`create`), names its `format()`, and converts unified catalog properties (`convertCatalogProperties`). | `formats.iceberg.IcebergCatalogFactory`, `META-INF/services/io.olake.fusion.FormatCatalogFactory` |
| `UnifiedCatalog` | A catalog over the format catalogs: `name`, `metastoreType`, `authenticationContext`, `listTables` returning `TableIDWithFormat`, `refresh`, `properties`. | `CommonUnifiedCatalog` |
| `CommonUnifiedCatalog` | The `UnifiedCatalog` implementation. Loads one `FormatCatalog` per enabled format through `ServiceLoader` and dispatches calls to them. | `CatalogUtil`, `CatalogMeta` (thrift), `TableMetaStore`, `server.catalog.ExternalCatalog` |
| `FusionTable<T>` | Format-neutral table handle: `id`, `name`, `format`, `properties`, `originalTable`, `currentSnapshot`. | `formats.iceberg.IcebergTable`, `TableSnapshot` |
| `TableSnapshot` | Snapshot view: `watermark`, `commitTime`, `id`, `operation`, `schemaId`. | `formats.iceberg.IcebergSnapshot`. Not the same type as `io.olake.fusion.table.TableSnapshot`. |
| `TableFormat` | Registry of formats as interned instances (`register`, `valueOf`, `values`, `in`). Not an enum. Public constant `ICEBERG`; `MIXED_ICEBERG` is registered in a static block only as a legacy name. Has Jackson `JsonSerializer` / `JsonDeserializer`. | `server.dashboard.JavalinJsonMapper`, `ServerTableIdentifier`, `CatalogUtil#tableFormats` |
| `TableIDWithFormat` | A `table.TableIdentifier` paired with its `TableFormat`. | `CommonUnifiedCatalog#listTables` |
| `ServerTableIdentifier` | Server-side table id (database id, catalog, database, table, format). Has a no-arg constructor for MyBatis. `equals` ignores `format`. | `TableRuntime`, persistence mappers in `fusion-ams` |
| `TableRuntime` | The server's per-table runtime interface: process states, group name, `ServerTableIdentifier`, `TableConfiguration`, metric registration, `dispose`. | `server.table.DefaultTableRuntime`, `process.ProcessFactory`, `config.TableConfiguration` |
| `SupportsProcessPlugins` | `trigger(Action)`, `install(Action, ProcessFactory)`, `enabled(Action)` for table processes. | `server.table.DefaultTableRuntime`, `server.table.AbstractTableRuntime` |
| `Action` | A named process action with supported formats and a weight; the name is at most 16 characters. `equals` uses name and formats. | `IcebergActions`, `TableRuntime#getProcessStates(Action)` |
| `IcebergActions` | Predefined actions, all for `ICEBERG`: `SYSTEM`, `REWRITE`, `DELETE_ORPHANS`, `EXPIRE_DATA`. | `server.scheduler.PeriodicTableScheduler` uses `SYSTEM` |
| `FusionPlugin`, `ActivePlugin` | Plugin base (`name()`) and plugins with `open(Map)` / `close()`. | `events.EventListener`, `fusion-ams` plugin managers |
| `StateField` | Runtime annotation that marks fields of a persistent object whose state the framework keeps consistent. | `server.persistence.StatedPersistentBase`, `server.optimizing.TaskRuntime`, `process.TableProcessState` |
| `IcebergFileEntry` | Manifest entry: snapshot id, sequence number (may be null for old `DELETED` entries), `ManifestEntryFields.Status`, `ContentFile`. | `utils` and scan code, `fusion-ams` table descriptors |
| `ErrorCodes` | Integer codes carried in thrift `AmoroException.errorCode`. | `exception.FusionRuntimeException`, `optimizer.common.AbstractOptimizerOperator` |
| `OptimizerProperties` | Property keys and defaults for optimizers, resource groups and resource containers. | `fusion-ams` containers and `DefaultOptimizingService`, `fusion-optimizer-common` `OptimizerConfig` / `Optimizer`, `SparkOptimizer` |
| `Constants` | Thrift service names `THRIFT_TABLE_SERVICE_NAME = "TableMetastore"` and `THRIFT_OPTIMIZING_SERVICE_NAME = "OptimizeManager"`, `EXTERNAL_RESOURCE_CONTAINER = "external"`, and the store names `INNER_TABLE_BASE` / `INNER_TABLE_CHANGE`. | `client.OptimizingClientPools`, `server.dashboard.utils.AmsUtil`, `FusionServiceContainer`, `data.FileNameRules` |
| `AmsClient` | `AmoroTableMetastore.Iface` + `Serializable`. | `table.blocker.RenewableBlocker` |
| `NoSuchTableException`, `NoSuchDatabaseException`, `AlreadyExistsException`, `FusionIOException` | Unchecked catalog and IO errors. | `FusionCatalog` implementations |

## How it works

**Building a unified catalog (server).** `server.catalog.ExternalCatalog` creates
`new CommonUnifiedCatalog(this::getMetadata, Maps.newHashMap())` inside `metaStore.doAs`. The
constructor reads the `CatalogMeta`, merges client properties into it
(`CatalogUtil#mergeCatalogProperties`), builds the `TableMetaStore` (`CatalogUtil#buildMetaStore`)
and calls `initializeFormatCatalogs`:

1. `ServiceLoader.load(FormatCatalogFactory.class)` finds the factories. `fusion-core` registers only
   `io.olake.fusion.formats.iceberg.IcebergCatalogFactory`.
2. `CatalogUtil#tableFormats(normalizedMetastoreType, catalogProperties)` decides which formats the
   catalog has: from the `table-formats` catalog property (`CatalogMetaProperties.TABLE_FORMATS`) if
   set (unknown names are dropped), otherwise from the catalog type (`glue`, `rest`, `custom`,
   `hadoop`, `filesystem` and `hive` give `ICEBERG`; `ams` without `table-formats` throws
   `IllegalArgumentException`).
3. For each matching factory, `convertCatalogProperties` then `create` builds the `FormatCatalog`.

**Dispatch.** `loadTable` and `listTables` look only in the `ICEBERG` format catalog.
`tableExists` checks every format catalog present. `listDatabases`, `createDatabase` and
`dropDatabase` go to the first format catalog found among `TableFormat.values()`. `dropDatabase`
refuses a non-empty database (`IllegalStateException`). `dropTable` loads the table to learn its
format first.

**Refresh.** `refresh()` is `synchronized`. It fetches a new `CatalogMeta` from the supplier and
rebuilds the meta store and the format catalogs only when the meta changed.

**Table formats.** `TableFormat.register(name)` interns one instance per name in a concurrent map;
`TableFormat.valueOf(name)` returns `null` for an unknown name (it does not throw). Values are
compared by name. `valueOf("MIXED_ICEBERG")` returns the legacy instance, which is not `ICEBERG`,
so checks such as `server.catalog.CatalogBuilder#checkSupported` reject it.

**Error codes on the wire.** `exception.FusionRuntimeException` maps its subclasses to the
`ErrorCodes` constants and builds the thrift `AmoroException(errorCode, errorName, message)`. The
optimizer's `AbstractOptimizerOperator` retries calls on `PERSISTENCE_ERROR_CODE` and
`UNDEFINED_ERROR_CODE`, and resets its token on `PLUGIN_RETRY_AUTH_ERROR_CODE`.

**Optimizer tag handshake.** The optimizer's `Optimizer` registers with
`OPTIMIZER_TAG = "optimizer-tag"` set to `OPTIMIZER_TAG_VALUE` (currently `"v0.1.5"`).
`DefaultOptimizingService` treats an optimizer with a non-blank tag as current: when it registers,
untagged registrations in the same group are retired; an untagged optimizer is refused with
`ForbiddenException` if a tagged one is already registered in the group.

## State, threads and concurrency

- `TableFormat`'s registry is a concurrent map; `register` is safe to call from any thread.
- `CommonUnifiedCatalog#refresh` is `synchronized`, but readers are not: `formatCatalogs`,
  `tableMetaStore` and `catalogProperties` are plain fields replaced as a whole, so a reader during a
  refresh sees either the old or the new set of catalogs (the map itself is rebuilt, not mutated).
- Everything else is constants, interfaces or immutable value objects, except the mutable setters on
  `ServerTableIdentifier` (used by MyBatis).

## Contracts

- `ErrorCodes` values (`-1`, `1000`-`1004`, `2001`-`2006`, `3001`) travel in thrift
  `AmoroException.errorCode` between server and optimizer.
- `Constants.THRIFT_TABLE_SERVICE_NAME` / `THRIFT_OPTIMIZING_SERVICE_NAME` are the multiplexed thrift
  service names; server and clients must agree.
- `OptimizerProperties` keys are sent in `OptimizerRegisterInfo.properties`, stored in resource and
  resource-group properties in PostgreSQL, and read from `config.yaml` container properties. Notable
  ones: `resource-id`, `ams-optimizing-uri`, `ams-home`, `optimizer-tag`, `execution-parallel`,
  `memory-size`, `group-name`, `heart-beat-interval`, `extend-disk-storage`, `disk-storage-path`,
  `memory-storage-size`, `max-input-file-size-per-thread` (default 512 MB), `cache-enabled`,
  `cache-max-entry-size`, `cache-max-total-size`, `cache-timeout`, `export.` prefix, and the Spark
  telemetry keys `spark.driver.memory`, `spark.executor.memory`, `spark.executor.cores`.
- `TableFormat` is serialized to JSON as its name (`JsonSerializer`) in the REST API.
- `META-INF/services/io.olake.fusion.FormatCatalogFactory` lists the catalog factories.
- `ServerTableIdentifier` is mapped by MyBatis (no-arg constructor and setters).

## Extending or changing it

- New format catalog: implement `FormatCatalogFactory` and `FormatCatalog` and list the factory in
  the services file, as `IcebergCatalogFactory` does. Not needed for Fusion's Iceberg-only scope;
  `CommonUnifiedCatalog#loadTable` and `#listTables` would also have to look in the new format.
- New error code: add the constant to `ErrorCodes`, add a `FusionRuntimeException` subclass and map
  it in `FusionRuntimeException`'s static `CODE_MAP` (see `exception/`). Decide whether the
  optimizer must retry on it (`AbstractOptimizerOperator#shouldRetryLater`).
- New optimizer property: add the key (and a `_DEFAULT`) to `OptimizerProperties`, then read it in
  `OptimizerConfig` (optimizer) and/or `AbstractOptimizerContainer` (server).
- New action: add a constant to `IcebergActions`; keep the name at 16 characters or fewer, or the
  `Action` constructor throws.

## Invariants and pitfalls

- **Rolling upgrades.** The server and the Spark optimizer can run different Fusion versions. Do not
  renumber `ErrorCodes`, rename thrift service names in `Constants`, or rename `OptimizerProperties`
  keys without handling the old value. Changing the retry codes changes optimizer behaviour only after
  the optimizer image is upgraded.
- **`OPTIMIZER_TAG_VALUE`** is a release marker. Only presence of the tag is checked today, not its
  value. An optimizer image without the tag cannot register once a tagged optimizer is in its group.
- `TableFormat.valueOf` returns `null` for unknown names; callers must handle it.
- **Legacy `MIXED_ICEBERG`.** Do not remove its registration in `TableFormat`: without it,
  `valueOf("MIXED_ICEBERG")` returns `null` and `CatalogUtil#tableFormats` silently drops it from
  `table-formats`, so a legacy catalog could fall back to the type default instead of being rejected.
  `TableFormat.values()` therefore contains two entries.
- `TableFormat.JsonDeserializer` passes `node.toString()` to `valueOf`. For a JSON string node this
  may include the surrounding quotes; check before relying on deserializing a `TableFormat` field.
- `CommonUnifiedCatalog#refresh` merges the client properties into the old meta, not the new one.
  `ExternalCatalog` passes an empty map, so this has no effect today.
- `ServerTableIdentifier#equals` and `hashCode` ignore `format`.
- There are two `AlreadyExistsException` classes (`io.olake.fusion` and `io.olake.fusion.exception`)
  and two `TableSnapshot` interfaces (`io.olake.fusion` and `io.olake.fusion.table`); check imports.

## Tests

- `fusion-core/src/test/java/io/olake/fusion/TestUnifiedCatalog.java` covers `CommonUnifiedCatalog`.
- `fusion-ams` `TestDefaultOptimizingService` covers optimizer registration.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am \
  -Dtest=TestUnifiedCatalog -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`aws`](aws/README.md), [`client`](client/README.md), [`config`](config/README.md),
  [`events`](events/README.md), [`exception`](exception/README.md), [`iceberg`](iceberg/README.md),
  [`log`](log/README.md), [`properties`](properties/README.md), [`resource`](resource/README.md),
  [`serialization`](serialization/README.md), [`hive`](hive/README.md) (Hive Metastore clients
  used by tests).
- `formats/iceberg` (the Iceberg `FormatCatalog`), `process` (`FusionProcess`, `ProcessFactory`),
  `table` (`TableIdentifier`, `TableMetaStore`), `utils` (`CatalogUtil`).
