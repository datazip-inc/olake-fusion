# `io.olake.fusion.utils`

Static helpers used across Fusion: catalog and property merging, cron evaluation, file deletion, SQL-filter parsing, statistics (Puffin) files, reflection, serialization and sizes.

## Role in Fusion

There is no single flow here; each class serves a different caller. The ones on Fusion's Iceberg path are listed first in the table. Several classes carry "Mixed" in their name but are used for Iceberg tables (`MixedFormatCatalogUtil`, `MixedTableUtil.getMixedTablePartitionSpecById`, `TablePropertyUtil` partition properties, `MixedDataFiles`).

Runs in: **both**. Server-heavy: `CatalogUtil`, `MixedFormatCatalogUtil`, `CronUtils`, `ExpressionUtil`, `TableFileUtil`, `IcebergThreadPools`, `JacksonUtil`. Optimizer-side: `SerializationUtil`, `MemorySize`, `ExceptionUtil`, `DynMethods`, `DynConstructors`, `StructLikeSet`. `CronUtils` is also evaluated inside `fusion-core` planning code (`CommonPartitionEvaluator`), which runs in the server.

Mixed-format only (out of scope): `NodeFilter`, `MixedTableUtil`'s optimized-sequence blob constants and `isTypeExist`, `TablePropertyUtil.isMixedTableStore` (used only to recognise and skip legacy mixed-iceberg stores).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `CronUtils` | `hasFiredInWindow(cronExpr, currentTimeMs, windowSec)` and `hasFiredInLastMinute(cronExpr)` (window 60 s) for 5-field Unix cron, in the JVM default time zone. Null/empty/invalid cron → `false`. | cron-utils `CronParser`; callers `fusion-ams` `TableRuntimeRefreshExecutor.evaluateCronTriggers`, `optimizing.plan.CommonPartitionEvaluator` |
| `CatalogUtil` | `tableFormats(...)` (from `table-formats` property, else from catalog type: `custom`/`rest`/`glue`/`hive` → ICEBERG, any other type, such as `ams`, → `IllegalArgumentException`); `buildMetaStore(CatalogMeta)` → `new TableMetaStore()` (default Hadoop configuration, same for every catalog); `mergeCatalogPropertiesToTable` (plain); `applyDefaultFileIO(metastoreType, props)` (default `io-impl` for `hive` and `custom`, see below). | `table.TableMetaStore`, `properties.CatalogMetaProperties` |
| `MixedFormatCatalogUtil` | `withIcebergCatalogInitializeProperties` (sets `type` or `catalog-impl`: Glue → `GlueCatalog`, REST → `RESTCatalog`, custom must give `catalog-impl`); `useMixedTableOperations` (wraps table ops); `mergeCatalogPropertiesToTable` (with log-store and self-optimizing filtering, used by `BasicUnkeyedTable.properties()`); `mergePersistedCatalogPropertiesToTable`; `buildCacheCatalog` (`cache-enabled`). | `formats.iceberg.IcebergCatalog`, `op.MixedTableOperations` |
| `ExpressionUtil` | `convertSqlFilterToIcebergExpression` parses `self-optimizing.filter` with JSqlParser into an Iceberg `Expression` (`=`, `!=`, `<`, `<=`, `>`, `>=`, `IN`, `IS [NOT] NULL`, `NOT`, `AND`, `OR`); `convertPartitionDataToDataFilter` builds a filter from partition values. | `AbstractOptimizingEvaluator`, `fusion-ams` `IcebergTableUtil` |
| `TableFileUtil` | Path helpers (`getFileName`, `getFileNameWithoutExt`, `getFileDir`, `getUriPath`, `getParent`), `deleteFiles`/`parallelDeleteFiles` (bulk delete if supported, else per file, returns deleted count), position-delete naming (`optimizingPosDeleteFileName`, `isOptimizingPosDeleteFile`). | `io.AuthenticatedFileIO`; `IcebergTableMaintainer` |
| `StatisticsFileUtil` | Puffin statistics files: `writerBuilder(table)...build().add(...).complete()` writes `<location>/data/puffin/<snapshotId>-<uuid>.puffin`; `reader(table).read(file, type, serializer)`; `getStatisticsFiles`; `copyToSnapshot`; `PartitionDataSerializer` (JSON map of partition path → value). | Iceberg `Puffin` |
| `PropertyUtil`, `CompatiblePropertyUtil` | Typed reads from `Map<String,String>` with defaults (`propertyAsBoolean/Int/Long/Double/String`, nullable variants). `CompatiblePropertyUtil` only delegates. | `fusion-ams` `TableConfigurations`, `OptimizingQueue` |
| `IcebergThreadPools` | Static planning and commit pools (`iceberg-planning-pool`, `iceberg-commit-pool`); `init(...)` from `FusionServiceContainer`, lazy default = CPU count. | `IcebergTableFileScanHelper`, `UnKeyedTableCommit` |
| `IcebergInMemoryLockManager` | Copy of Iceberg's in-memory `LockManager` with an NPE fix on release. | `op.MixedHadoopTableOperations` |
| `ContentFiles` | `isDataFile`, `isDeleteFile`, `asDataFile`, `asDeleteFile`. | `io.reader.CombinedDeleteFilter`, `UnKeyedTableCommit` |
| `SerializationUtil` | `simpleSerialize`/`simpleDeserialize` (Java serialization, used for task input/output and Postgres blobs), `kryoSerialize`/`kryoDeserialize`, `createJavaSimpleSerializer()`. | `serialization.SimpleSerializer`, `KryoSerializer` |
| `LegacyClassNames` | `toCurrent(name)` maps `org.apache.amoro.X` (and array descriptors) to `io.olake.fusion.X`, renaming `Amoro*` classes listed in `RENAMED_CLASSES` (for example `AmoroTable` → `FusionTable`); shaded `org.apache.amoro.shade.*` names are untouched. | `serialization.LegacyAwareObjectInputStream`, `KryoSerializer`, `config.Configurations`, `server.resource.Containers`, `server.terminal.TerminalManager` |
| `MemorySize` | Parse and format sizes (`parseBytes("64mb")`, units b/k/m/g/t, base 1024). | `DeleteCache`, `ConfigOptions`, optimizer config |
| `JacksonUtil` | Shared `ObjectMapper` (enums as index, ignore unknown properties) and `JsonNode` getters. | `FusionServiceContainer`, plugin config (`PluginConfiguration`, `AbstractPluginManager`) |
| `CommonUtil` | `byteToXB(long)` (human-readable size, e.g. `12.34MB`), `getFileName`. | descriptor DTOs, `MetricsSummary` |
| `MixedTableUtil` | `getMixedTablePartitionSpecById` (Iceberg: `asUnkeyedTable().specs().get(specId)`), `baseStore`, `findLatestValidSnapshot`; optimized-sequence blob constants and `isTypeExist`. | `AbstractPartitionPlan`, `AbstractOptimizingPlanner`, `ExpressionUtil`, `MixedAndIcebergTableDescriptor` |
| `TablePropertyUtil` | Partition-property JSON encode/decode (`table.partition-properties`), `getPartitionProperties`, `getPartitionLongProperties`, `getTableWatermark`; `isMixedTableStore` and `LEGACY_MIXED_ICEBERG_FORMAT` to recognise legacy mixed-iceberg stores. | `BasicUnkeyedTable.partitionProperty()`, `op.PartitionPropertiesUpdate`, `op.MixedUpdate`, `fusion-ams` `DefaultTableService`, `RestCatalogService` |
| `MixedDataFiles` | Parses a partition path (`a=1/b=x`) into a `GenericRecord` (`data`); `fromPartitionString`. | `StatisticsFileUtil`, `TablePropertyUtil`, dashboard |
| `DynMethods`, `DynConstructors` | Reflection helpers copied from parquet-common; `hiddenImpl` reaches private methods. | `io.reader.CombinedDeleteFilter`, `hive.*`, `server.authentication.HttpAuthenticationFactory`, `OptimizerExecutor` |
| `StructLikeSet` | Set of `StructLike` over a memory or spillable map (`createMemorySet`, `createSpillableSet`). | `io.reader.Deletes`, `map.StructLikeCollections` |
| `ExceptionUtil` | `getErrorMessage(t, maxLength)`: stack trace truncated. | `OptimizerExecutor`, `SparkOptimizerExecutor`, `OptimizingQueue`, `DefaultTableRuntime` |
| `IdGenerator` | `randomId()` (non-negative long from a UUID), `generateUpstreamId()`. | `FileNameRules` |
| `ManifestEntryFields`, `LocalFileUtil`, `ReflectionUtils`, `NodeFilter` | Manifest-entry field ids and `Status`; local directory delete/mkdir (RocksDB); static no-arg method invoke; mixed tree-node filter. | `scan.TableEntriesScan`, `IcebergFileEntry`, `map.RocksDBBackend`, `io.reader.AbstractUnkeyedDataReader` |

## How it works

- **Cron triggers.** Every refresh tick, `TableRuntimeRefreshExecutor.evaluateCronTriggers` checks FULL, then MAJOR, then MINOR with `CronUtils.hasFiredInLastMinute`. `hasFiredInWindow` finds the cron's last execution before now and returns true if now is within `[last, last + window)`. The planner then re-checks with the same method in `CommonPartitionEvaluator` (`reachMajorInterval`, `reachFullInterval`, minor trigger).
- **Catalog → Iceberg properties.** `withIcebergCatalogInitializeProperties` sets `type` from the metastore type, then removes `type` whenever `catalog-impl` is present, as Iceberg requires one or the other. It then calls `CatalogUtil.applyDefaultFileIO`: for `hive` and `custom` catalogs without `io-impl`, it sets `io-impl=org.apache.iceberg.io.ResolvingFileIO` (`S3FileIO` for `s3`, `s3a` and `s3n` paths, `HadoopFileIO` otherwise), because Iceberg's `HiveCatalog` and `JdbcCatalog` otherwise default to `HadoopFileIO`, which cannot read `s3://` paths. Glue and REST catalogs are left unchanged. `TerminalManager.applyClientProperties` applies the same default to the terminal's Spark catalog.
- **Effective table properties.** See [`../table`](../table/README.md).
- **File deletion.** `TableFileUtil.deleteFiles` uses `asBulkFileIO().deleteFiles` when available (S3FileIO is bulk-capable through the adapter) and counts `BulkDeletionFailureException.numberFailedObjects()` as failures.

## State, threads and concurrency

- `IcebergThreadPools` and `IcebergInMemoryLockManager` keep static JVM-wide state. `IcebergInMemoryLockManager.HEARTBEATS` is a plain `HashMap` mutated from callers and from `release`.
- `ReflectionUtils.CLASS_MAP` is a plain `HashMap` used with `computeIfAbsent` from any thread.
- Everything else is stateless.

## Contracts

- **Cron format**: 5 fields (`minute hour day-of-month month day-of-week`), evaluated in the JVM default zone. OLake-UI sends e.g. `0 * * * *`, `0 */8 * * *`.
- **Puffin path**: `<table location>/data/puffin/<snapshotId>-<uuid>.puffin`.
- **Java serialization** through `SerializationUtil.simpleSerialize` is the wire and storage format of task inputs; `LegacyClassNames` is what keeps rows written under the old `org.apache.amoro` names readable.

## Extending or changing it

- Add a SQL operator to `self-optimizing.filter`: extend `ExpressionUtil.convertSparkExpressionToIceberg` and `convertValue`; add a case to `TestExpressionUtil`.
- Add a renamed class to `LegacyClassNames.RENAMED_CLASSES` whenever a serialized class's simple name changes.
- Two `mergeCatalogPropertiesToTable` methods exist (`CatalogUtil` and `MixedFormatCatalogUtil`) with different behaviour; pick deliberately.

## Invariants and pitfalls

- **Cron window.** `hasFiredInLastMinute` only sees a fire for 60 s. If the refresh executor's interval is longer than 60 s, or planning happens more than 60 s after the fire, the trigger is missed or the planner disagrees with the refresh executor. Server time zone changes shift every schedule.
- `CronUtils` swallows parse errors and returns `false`, so an invalid cron from OLake-UI silently never runs.
- `CatalogUtil.tableFormats` drops `table-formats` entries that `TableFormat.valueOf` does not know (it returns `null`, filtered out); if none is left, it falls back to the catalog type. The legacy `MIXED_ICEBERG` name is registered so that it is kept and rejected later rather than dropped.
- `ExpressionUtil` TIMESTAMP literals are converted with `toEpochSecond() * 1_000_000`, dropping sub-second precision; BOOLEAN values are read from a `Column` node (`true`/`false` parsed as column names).
- `StatisticsFileUtil.getStatisticsFiles` returns `null`, not an empty list, when stats files exist but none for the given snapshot.
- `MemorySize.parseBytes` accepts only integer numbers (`1.5g` fails).

## Tests

`fusion-core/src/test/java/io/olake/fusion/utils/`: `TestExpressionUtil`, `TestUnkeyedExpressionUtil`, `TestMixedFormatCatalogUtil`, `TestStatisticsFileUtil`, `TestTablePropertyUtil`, `TestMixedDataFiles`, `TestFileUtil`, `TestCompatiblePropertyUtil`, `TestLegacySerializedClassNames`, `MemorySizeTest`, `JacksonUtilTest`, `ReflectionUtilsTest`, `TestWatermarkGenerator`. There is no unit test for `CronUtils`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestExpressionUtil -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`map`](map/README.md), [`../table`](../table/README.md), [`../io`](../io/README.md), [`../io/reader`](../io/reader/README.md)
