# `io.olake.fusion.server.utils`

Server-side helpers: the Thrift exception proxy, environment-variable config overrides, usage
telemetry, process-id generation, gzip, precondition checks, and Iceberg helpers used by planning,
health scoring and internal catalogs.

## Role in Fusion

All classes run in the Fusion server. They have no lifecycle of their own (except `Telemetry`'s
background thread) and are called from:

- `FusionServiceContainer`: `EnvConfig`, `ThriftServiceProxy`, `Telemetry.configure`.
- The compaction lifecycle: `IcebergTableUtil#createOptimizingPlanner` (from `OptimizingQueue`),
  `IcebergTableUtil#createOptimizingEvaluator` and `#getSnapshotId` (from
  `TableHealthScoreExecutor`), `IcebergTableUtil#getSnapshot` and `#findLatestOptimizingSnapshot`
  (from `DefaultTableRuntime`), `SnowflakeIdGenerator` (process ids in `DefaultTableRuntime` and
  `ActionCoordinator`; expiry bounds in `OptimizingExpiringExecutor` and `DefaultTableManager`).
- Persistence: `CompressUtil` (`TaskFilesPersistence`, `Object2ByteArrayConvert`).
- Internal (`ams`-type) catalogs: `InternalTableUtil` (`table/internal/*`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ThriftServiceProxy<S>` | `createProxy(iface, service, exceptionTransfer)` returns a JDK dynamic proxy that unwraps `InvocationTargetException`, logs `Thrift service:<class>.<method> execute failed`, and rethrows `exceptionTransfer.apply(cause)`. | `FusionServiceContainer#initThriftService`, `FusionRuntimeException::normalize` / `::normalizeCompatibly` |
| `EnvConfig` | `fromEnv(env)`: config keys from `AMS_*` (legacy) and `FUSION_*` variables, `FUSION_*` winning; script variables such as `FUSION_HOME`, `FUSION_CONF_DIR`, `FUSION_LOG_DIR` are excluded. Key conversion is `ConfigHelpers.convertConfigurationKeys`: drop `<PREFIX>_`, lower-case, `__` -> `-`, `_` -> `.`. | `FusionServiceContainer.ConfigurationHelper#initEnvConfig` |
| `Telemetry` | Fire-and-forget usage events to `https://analytics.olake.io/mp/track`. Singleton; never throws or blocks callers. | `PlatformPropertyStore`, `FusionServiceContainer#getSparkConfig`, `TelemetryController`, `OptimizingQueue`, `DefaultCatalogManager`, `DefaultOptimizingService` |
| `SnowflakeIdGenerator` | 54-bit ids: timestamp in 10 ms units shifted left 13, 5-bit machine id, 8-bit sequence. `generateId` (synchronized), `getMinSnowflakeId(timestamp)`, `extractTimestamp(id)` (seconds). | `DefaultTableRuntime`, `ActionCoordinator`, `OptimizingExpiringExecutor`, `DefaultTableManager` |
| `IcebergTableUtil` | Snapshot lookups; `createOptimizingEvaluator` / `createOptimizingPlanner` build `IcebergOptimizerEvaluator` / `IcebergOptimizingPlanner` for `TableFormat.ICEBERG` tables and throw `IllegalStateException` for any other format. Also file-set helpers (see pitfalls). | `DefaultTableRuntime`, `OptimizingQueue`, `TableHealthScoreExecutor` |
| `InternalTableUtil` | Helpers for internal catalogs: `newIcebergFileIo(CatalogMeta)` (S3FileIO for `s3`, OSS for `oss`, else Hadoop, unless `io-impl` is set), `genNewMetadataFileLocation(base, current)` (next `<version>-<uuid>` metadata file), `genMetadataFileLocation` (honours `write.metadata.path`). | `IcebergInternalTableOperations`, `InternalIcebergCreator`, `InternalIcebergHandler` |
| `CompressUtil` | `gzip` / `unGzip` byte arrays (`null` in, `null` out). | `TaskFilesPersistence`, `Object2ByteArrayConvert` |
| `PreconditionUtils` | `checkExist` (`ObjectNotExistsException`), `checkNotExist` (`AlreadyExistsException`), `checkNotNullOrEmpty`. | `LoginController` |

## How it works

### Planner and evaluator creation

`IcebergTableUtil#createOptimizingPlanner(tableRuntime, table, availableCore, maxInputSizePerThread, processId)`:

1. Builds a partition filter: `Expressions.alwaysTrue()` if `tableRuntime.getPendingInput()` is
   `null`, otherwise the OR of `ExpressionUtil.convertPartitionDataToDataFilter` for each pending
   partition, so planning only scans partitions the evaluator flagged.
2. Reads the table's `OptimizingConfig` and the last Minor (Lite), Major (Medium) and Full
   optimizing times from the runtime.
3. Gets the snapshot with `getSnapshot(MixedTable, DefaultTableRuntime)` (a `BasicTableSnapshot` of
   the runtime's current snapshot id).
4. Returns `new IcebergOptimizingPlanner(...)`.

`createOptimizingEvaluator` does the same without the filter and returns an
`IcebergOptimizerEvaluator`; `maxPendingPartitions` comes from
`refresh-tables.max-pending-partition-count`.

`findLatestOptimizingSnapshot` walks snapshots newest first and returns the first `replace`
snapshot whose summary contains `CommitMetaProducer.OPTIMIZE.name()`, which marks Fusion's own
compaction commits.

### Telemetry

1. `FusionServiceContainer#initConfig` calls `Telemetry.configure(telemetry.disabled)`.
2. The constructor builds an `HttpClient` (10 s timeouts) and a single-thread `olake-telemetry`
   daemon executor with a 256-slot queue that discards on overflow, then `initAsync` fetches
   `https://ipinfo.io/json` for IP and location (given up after 20 s).
3. Each `track*` builds the payload under `try/catch` and schedules `dispatchEvent` after init.
   `dispatchEvent` reads the install id (`PlatformPropertyStore.get("telemetry.install_id")`,
   cached), skips the event if there is none, adds `os`, `arch`, `num_cpu`, `ip_address`,
   `location`, `distinct_id`, `time`, `event_original_name`, and posts it asynchronously.
4. OLake-UI pushes the install id with `PUT /api/ams/v1/telemetry/install-id`
   (`TelemetryController`), which stores it and calls `useInstallId`.

Events: `Optimization Started - Fusion`, `Optimization Completed - Fusion` (from
`OptimizingQueue`), `Catalog Created - Fusion`, `Catalog Updated - Fusion`, `Failure - Fusion`
(command `create_catalog`, category `dns_resolution_failed` or `unclassified` plus `error_type`;
from `DefaultCatalogManager`), `Installed Fusion` (from `DefaultOptimizingService`, once per
process, with `optimizer_parallelism` and `deployment_mode` `HELM` if `KUBERNETES_SERVICE_HOST` is
set, else `DOCKER`). Optimization events report `optimization_type` as `LITE` for Minor, `MEDIUM`
for Major, otherwise the enum name (`FULL`), plus `table_size (GB)` and the Spark settings
`spark_driver_memory`, `spark_executor_memory`, `spark_executor_cores`.

## State, threads and concurrency

- `Telemetry`: one daemon thread `olake-telemetry`, bounded queue (256), drops when full. Volatile
  `userID`, `ipAddress`, `locationInfo`, static `configuredDisabled`. The install id is persisted
  in `platform_property` (created by `postgres/migration/V1__platform_property.sql`).
- `SnowflakeIdGenerator`: `generateId` is `synchronized`; `sequence` and `lastTimestamp` are per
  instance, so each generator instance is its own id space (`DefaultTableRuntime` and
  `ActionCoordinator` hold separate static instances, both with machine id 0).
- Everything else is stateless.

## Contracts

- Env overrides: `FUSION_<KEY>` / `AMS_<KEY>`, for example `AMS_DATABASE_URL` -> `database.url`,
  `AMS_SERVER__EXPOSE__HOST` -> `server-expose-host`. OLake-Helm and OLake-UI's compose set the
  `AMS_` form.
- Telemetry: off when env `TELEMETRY_DISABLED=true` or config `telemetry.disabled: true`. Event
  names and property names above are consumed by OLake's analytics.
- Process ids: optimizing process ids are snowflake ids; `getMinSnowflakeId(time)` is used as a
  lower bound in queries by process id, so ids must stay time-ordered.
- Gzip format of persisted task files (`CompressUtil`) is read back from Postgres; changing it
  breaks existing rows.

## Extending or changing it

- New telemetry event: add a public `track*` method that calls `sendEvent(name, supplier)`; build
  all properties inside the supplier so failures stay contained. Never call `httpClient` directly.
- New failure category: add to `FAILURE_CATEGORIES` (more specific types first) and `Category`.
- Planner/evaluator changes belong in `fusion-core`'s `IcebergOptimizingPlanner` /
  `IcebergOptimizerEvaluator`; keep this class as the factory.

## Invariants and pitfalls

- `Telemetry` must never throw or block: it is called from `finally` blocks in
  `DefaultCatalogManager` and from `OptimizingQueue` while processes complete.
- Without an install id in `platform_property`, every event is dropped silently.
- `SnowflakeIdGenerator#generateId` throws `RuntimeException("Clock moved backwards!")` if the
  clock goes back, and its timestamp unit is 10 ms despite the `EPOCH_SECONDS` name.
- `IcebergTableUtil#getAllContentFilePath`, `#getAllStatisticsFilePath`,
  `#getDanglingDeleteFiles` and `#getAllManifestFiles` have no callers in `fusion-ams/src/main`;
  `IcebergTableMaintainer` uses the copies in `fusion-core`'s
  `io.olake.fusion.formats.iceberg.utils.IcebergTableUtil`. Only `TestIcebergTableUtil` calls the
  server copy of `getAllManifestFiles`.
- `IcebergTableUtil#getSnapshot(MixedTable, ...)`, `#createOptimizingEvaluator` and
  `#createOptimizingPlanner` still take Amoro's `MixedTable` interface; only `TableFormat.ICEBERG`
  tables get past the format check.
- `ThriftServiceProxy` logs with the `TableManagementService` logger for both services.
- `InternalTableUtil#newIcebergFileIo` assumes the catalog has a `warehouse` property
  (`NullPointerException` otherwise).

## Tests

- `fusion-ams/src/test/java/io/olake/fusion/server/utils/`: `TestEnvConfig`, `TestTelemetry`.
- `fusion-ams/src/test/java/io/olake/fusion/server/util/`: `TestCompressUtil`,
  `TestSnowflakeIdGenerator`, `TestIcebergTableUtil`.
- Planner and evaluator factories: `optimizing/plan/TestOptimizingPlanner`,
  `optimizing/plan/TestOptimizingEvaluator`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestTelemetry -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): `FusionServiceContainer`, `DefaultOptimizingService`.
- [`../optimizing/`](../optimizing/README.md): `OptimizingQueue` (planner, telemetry).
- [`../scheduler/`](../scheduler/README.md): `TableHealthScoreExecutor`, `OptimizingExpiringExecutor`.
- [`../table/`](../table/README.md): `DefaultTableRuntime`, `table/internal/*`.
- [`../persistence/`](../persistence/README.md): `PlatformPropertyStore`, `TaskFilesPersistence`.
