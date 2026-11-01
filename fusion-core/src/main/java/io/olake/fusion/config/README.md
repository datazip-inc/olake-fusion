# `io.olake.fusion.config`

The typed configuration framework (`ConfigOption`, `ConfigOptions`, `Configurations`,
`ConfigHelpers`), adapted from Apache Flink, and the per-table configuration objects
(`TableConfiguration`, `OptimizingConfig`, `DataExpirationConfig`, `TagConfiguration`).

## Role in Fusion

The code is in `fusion-core`, used by both sides, mostly by the **server**:

- **Server config.** `fusion-ams` defines its options in `FusionManagementConf` with
  `ConfigOptions.key(...)`. `FusionServiceContainer` loads `config.yaml`, flattens it, overlays
  environment variables (`server.utils.EnvConfig`, using `ConfigHelpers#convertConfigurationKeys`),
  decrypts sensitive values (`config.shade.utils.ConfigShadeUtils`), and builds a `Configurations`
  with `Configurations#fromObjectMap`.
- **Table config.** The server's `server.table.TableConfigurations#parseTableConfig` turns Iceberg
  table properties into a `TableConfiguration`. `TableRuntime#getTableConfiguration` exposes it to the
  schedulers, and `formats.iceberg.maintainer.IcebergTableMaintainer` and
  `maintainer.TableMaintainerContext` read it.
- **Optimizer.** The optimizer does not use `TableConfiguration`. Duration and memory-size parsing
  helpers (`ConfigHelpers.TimeUtils`) are shared code.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ConfigOption<T>` | Immutable option: `key()`, `defaultValue()`, `hasDefaultValue()`, `description()`, plus package-private type class and list flag. `withDescription` returns a copy. | `ConfigOptions`, `Configurations` |
| `ConfigOptions` | Builder: `key(k)` → `OptionBuilder` → typed builder (`booleanType`, `intType`, `longType`, `floatType`, `doubleType`, `stringType`, `durationType`, `enumType`, `mapType`, `memorySizeType`) → `defaultValue`/`noDefaultValue`, or `asList()` → `defaultValues`/`noDefaultValue`. | `ConfigOption`, `utils.MemorySize` |
| `Configurations` | Serializable key/value store (`HashMap<String, Object>`). Typed getters (`get`, `getOptional`, `getString`, `getInteger`, `getLong`, `getBoolean`, `getFloat`, `getDouble`, `getEnum`, `getDurationInMillis`, `getClass`), setters, `toMap`, `addAll`, `clone`. | `ConfigHelpers`, `utils.LegacyClassNames` |
| `ConfigHelpers` | Type conversion (`convertValue`, `convertToList`, `convertToProperties`, `convertToEnum`, `convertToDuration`, `convertToMemorySize`, `convertToString`), env-key conversion (`convertConfigurationKeys`), `createConfiguration(Properties)`, `splitPaths`, and `TimeUtils` (`parseDuration`, `formatWithHighestUnit`, `getStringInMillis`). | `StructuredOptionsSplitter` |
| `StructuredOptionsSplitter` | Splits strings on a delimiter with single/double quotes and doubled-quote escapes (`splitEscaped`); quotes values that need it (`escapeWithSingleQuote`). | `ConfigHelpers` |
| `ConfigurationException` | Unchecked error with the offending `getConfigKey()`; thrown by `getDurationInMillis` on overflow. | `Configurations` |
| `TableConfiguration` | Per-table settings: snapshot expiry, snapshot TTL and min count, Flink checkpoint retention, change data TTL, orphan clean, dangling-delete clean, plus nested `OptimizingConfig`, `DataExpirationConfig`, `TagConfiguration`. | `TableRuntime`, `server.table.TableConfigurations` |
| `OptimizingConfig` | Optimizing settings parsed from `self-optimizing.*` table properties, including the three cron triggers, target size and quota. `maxFragmentSize()` = `targetSize / fragmentRatio`; `maxDuplicateSize()`; `isMetadataBasedTriggerEnabled()` = `evaluationFallbackInterval >= 0`. | evaluators and planners in `optimizing`, `server.table.DefaultTableRuntime` |
| `DataExpirationConfig` | `data-expire.*` settings with enums `ExpireLevel` (`PARTITION`, `FILE`; `fromString`) and `BaseOnRule` (`LAST_COMMIT_TIME`, `CURRENT_TIME`). | `server.scheduler.inline.DataExpiringExecutor` |
| `TagConfiguration` | `tag.auto-create.*` settings with enum `Period` (`DAILY`, `HOURLY`; `getTagTime`, `generateTagName`). | `server.scheduler.inline.TagsAutoCreatingExecutor` |

## How it works

**Defining an option** (server example from `FusionManagementConf`):

```java
public static final ConfigOption<Integer> OPTIMIZING_SERVICE_THRIFT_BIND_PORT =
    ConfigOptions.key("thrift-server.optimizing-service.bind-port").intType().defaultValue(1261);
```

**Reading.** `Configurations#get(option)` = `getOptional(option).orElseGet(option::defaultValue)`.
`getOptional` looks up the raw value under `option.key()` only (there are no fallback or
deprecated keys, despite the `ConfigOptions` javadoc example) and converts it with
`ConfigHelpers#convertValue`, or `#convertToList` for list options. A conversion failure becomes
`IllegalArgumentException("Could not parse value '...' for key '...'")`.

**Conversion rules (`ConfigHelpers`):**
- Numbers accept the boxed type or a string; `Long` → `Integer` and `Double` → `Float` check range.
- Booleans accept only `true` / `false`, case insensitive.
- Enums match `toString()` ignoring case.
- Durations: `TimeUtils#parseDuration`, number followed by an optional unit (`d`/`day(s)`, `h`/`hour(s)`,
  `min`/`minute(s)`, `s`/`sec(s)`/`second(s)`, `ms`/`milli(s)`/`millisecond(s)`, `µs`/`micro(s)`,
  `ns`/`nano(s)`); no unit means milliseconds.
- Memory sizes: `MemorySize#parse`.
- Lists: `;`-separated; maps: `k1:v1,k2:v2`; both support quoting through `StructuredOptionsSplitter`.
- `convertToString` is the inverse used by `toMap()` (durations as `formatWithHighestUnit`, e.g. `1 min`).

**Environment variables to keys.** `ConfigHelpers#convertConfigurationKeys(prefix, env)` keeps
variables whose name starts with `prefix`, removes the first `prefix_`, lower-cases, turns runs of
two or more `_` into `-`, and the remaining `_` into `.`. So `AMS_DATABASE_URL` → `database.url` and
`AMS_SERVER__EXPOSE__HOST` → `server-expose-host`. (The method's javadoc says
`ams.database.password`; the code strips the prefix, so the key is `database.password`.) The server's
`EnvConfig#fromEnv` applies the legacy `AMS` prefix first and then `FUSION`, so `FUSION_*` wins, and
environment values override `config.yaml`.

**Table configuration.** `TableConfigurations#parseTableConfig(properties)` (in `fusion-ams`) builds a
new `TableConfiguration` from the table's properties each time; defaults come from
`table.TableProperties`. The database stores the raw properties (`table_runtime.table_config`), not
the object. Change handlers compare old and new objects with `equals`.

## State, threads and concurrency

- `Configurations` synchronizes every access on its internal map, so one instance can be shared.
  `hashCode` and `equals` read without the lock.
- `ConfigOption` and the builders are immutable.
- `TableConfiguration` and nested configs are mutable beans (fluent setters); treat a parsed instance
  as read-only and build a new one on change.

## Contracts

- The YAML keys of the server's `config.yaml` (under `fusion:` or the legacy section) and the
  `FUSION_*` / `AMS_*` environment variables map to `ConfigOption` keys by the rule above. OLake-Helm
  and OLake-UI's compose set `AMS_*` variables.
- Table property names read into `OptimizingConfig` (listed as comments on its fields), for example
  `self-optimizing.enabled`, `self-optimizing.quota`, `self-optimizing.target-size`,
  `self-optimizing.minor.trigger.cron`, `self-optimizing.major.trigger.cron`,
  `self-optimizing.full.trigger.cron`. OLake-UI writes these through the terminal API.
- The duration string format (`TimeUtils`) is used in config files and table properties.

## Extending or changing it

- New server option: add a `ConfigOption` to `FusionManagementConf` with `withDescription`, then
  regenerate the configuration golden file (`ConfigurationsTest`,
  `UPDATE=1 ./mvnw test -pl fusion-ams -am -Dtest=ConfigurationsTest`).
- New table setting: add a field with getter and fluent setter to the right config class, add it to
  `equals`, `hashCode` and `toString`, and parse it in `server.table.TableConfigurations`. Without
  the `equals` entry, a change to the property does not trigger `handleConfigChanged`.
- New value type: add a branch to `ConfigHelpers#convertValue` and a builder method in
  `ConfigOptions.OptionBuilder`.

## Invariants and pitfalls

- `Configurations#equals` only checks this object's keys against the other; it is not symmetric.
- `Configurations#contains` and `removeConfig` look at the main key only.
- `OptimizingConfig.maxCommitRetryCount` has no getter or setter: it is always `0` but is part of
  `equals`/`hashCode`.
- `OptimizingConfig#maxFragmentSize` divides by `fragmentRatio`; a `0` ratio throws.
- A cron field that is `null` means that trigger is off (field comments in `OptimizingConfig`).
- `getClass` maps legacy `org.apache.amoro.*` class names through `LegacyClassNames#toCurrent`.
- Env keys: a variable such as `AMSX_Y` also passes the `startsWith("AMS")` filter.

## Tests

- `fusion-core/src/test/java/io/olake/fusion/config/ConfigHelpersTest.java` (`TimeUtils`).
- `fusion-ams/src/test/java/io/olake/fusion/config/ConfigurationsTest.java` (option docs golden file,
  `getDurationInMillis`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am \
  -Dtest=ConfigHelpersTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`config/shade`](shade/README.md), [`config/shade/impl`](shade/impl/README.md),
  [`config/shade/utils`](shade/utils/README.md)
- [`properties`](../properties/README.md) (catalog property prefixes such as `table.`)
- [`io.olake.fusion`](../README.md) (`TableRuntime`)
- `table` (`TableProperties`: names and defaults), `utils` (`MemorySize`, `LegacyClassNames`),
  `fusion-ams` `FusionManagementConf`, `server.utils.EnvConfig`, `server.table.TableConfigurations`
