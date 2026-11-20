# `io.olake.fusion.server.dashboard.utils`

Static helpers for the HTTP layer: request signatures, the single-page token check, catalog
property splitting, table statistics, optimizer-quota maths, and small formatting functions.

## Role in Fusion

Called by the controllers, `MixedAndIcebergTableDescriptor`, `FusionServiceContainer` (the
`before` filter), `DefaultTableManager`, `DefaultCatalogManager`, `TerminalManager`,
`FusionManagementConfValidator` and the terminal classes. Nothing here holds state except two
static `SimpleDateFormat`s.

## Key classes

| Class | Responsibility | Main callers |
| --- | --- | --- |
| `ParamSignatureCalculator` | `getMD5`; `generateParamStringWithValueList` (sorted `key + URL-decoded first non-blank value`, used for API-token signatures); `generateParamStringWithValue` (same for single values); `SIMPLE_DATE_FORMAT` (`yyyyMMdd`) | `ApiTokenController`, `CommonUtil` |
| `CommonUtil` | `checkSinglePageToken` (the `token` query-param check in the `before` filter), `generateTablePageToken`, `telnetOrPing` / `telnet` / `ping` | `FusionServiceContainer`, `TableController` |
| `PropertiesUtil` | Split catalog properties into catalog and table parts by `CatalogMetaProperties.TABLE_PROPERTIES_PREFIX` (`extractTableProperties`, `extractCatalogMetaProperties`, `unionCatalogProperties`); `sanitizeProperties` (trim, drop empty keys and null values); `putNotNullProperties` | `CatalogController`, `OptimizerGroupController`, `TableStatCollector`, `TableMetadata` |
| `AmsUtil` | `byteToXB` (size string like `12.00MB`), `longOrNull`, `formatString` (`ICEBERG` to `Iceberg`), `getAMSThriftAddress` (own Thrift address from `server-expose-host` and the table or optimizing service bind port), `lookForBindHost`, `getNotDeprecatedAndNotInternalStaticFields` | descriptor, `TableController`, `TerminalManager`, `DefaultCatalogManager`, `FusionManagementConfValidator` (`lookForBindHost`) |
| `TableStatCollector` | Build `TableStatistics` from a table's current snapshot summary and snapshot list (`fillTableStatistics`); `union` copies the base statistics when the change statistics are `null` (always, for Iceberg) | `MixedAndIcebergTableDescriptor` |
| `OptimizingUtil` | `buildTableOptimizeInfo`: status, duration, quota (`self-optimizing.quota` from the table config; values above 1 are thread counts, else a share of `threadCount`), quota occupation over `FusionServiceConstants.QUOTA_LOOK_BACK_TIME`, and pending or in-progress file counts | `DefaultTableManager.queryTableOptimizingInfo` |
| `DesensitizationUtil` | `desensitize` (always `********`) and `isDesensitized` | `CatalogController`, `TerminalSessionContext`, `LocalSessionFactory` |

## How it works

- **API-token signature** (with `ApiTokenController`):
  `md5(apiKey + generateParamStringWithValueList(params without apiKey, signature) + secret)`; an
  empty param string is replaced by `SIMPLE_DATE_FORMAT.format(new Date())`.
- **Single-page token** (`checkSinglePageToken`), run by the `before` filter when the query has
  `token`: returns at once if the URI contains `/login/current` or `/versionInfo`; else takes
  `catalog`, `db`, `table` from the query, or, if all three are absent, from the path segments after
  `catalogs`, `dbs`, `tables`; then requires
  `token == generateTablePageToken(catalog, db, table)`, which is
  `md5(p + p + p)` with `p = "catalog"+catalog+"db"+db+"table"+table`.
- **Quota occupation** (`calculateQuotaOccupy`): finished-task quota from
  `TaskRuntime.TaskQuota.getQuotaTime(startTime)` (expired entries are removed from the passed list)
  plus running time of tasks not `CANCELED`, `SUCCESS` or `FAILED`.

## State, threads and concurrency

- `ParamSignatureCalculator.SIMPLE_DATE_FORMAT` is a shared `SimpleDateFormat`, which is not
  thread-safe. Concurrent signature checks of requests without query parameters can format a wrong
  date and fail the check.
- `calculateQuotaOccupy` mutates the `quotas` list it receives.

## Contracts

- The signature algorithm must match OLake-UI's `calculateSignature` and `generateEncryptString`
  in `olake-ui/server/internal/services/optimization/client.go`. OLake-UI formats the fallback date
  in UTC; Fusion uses the JVM default time zone. They agree only if the server runs in UTC.
- `PropertiesUtil` decides which catalog keys appear as `tableProperties` in
  `GET /catalogs/{name}`; OLake-UI reads `table.self-optimizing.*` defaults from there.

## Extending or changing it

Keep helpers static and side-effect free. Any change to `ParamSignatureCalculator` must be made in
OLake-UI's client in the same release.

## Invariants and pitfalls

- **`checkSinglePageToken` is an auth bypass.** The token is an unsalted hash of names an attacker
  can choose (`catalog`, `db`, `table` query parameters), and the `before` filter skips the normal
  auth check whenever `token` is present. Every route is reachable with a self-computed token.
- The API-token signature covers only the query string, not the path, method or body. A signature
  for a request without query parameters is valid for any path and body until the date changes.
- `CommonUtil.telnetOrPing`, `AmsUtil.getFileName`, `AmsUtil.getStackTrace`,
  `AmsUtil.getNotDeprecatedAndNotInternalStaticFields`, `PropertiesUtil.removeHiddenProperties`
  and `TableStatCollector.buildBaseTableSnapshotInfo` have no callers in `fusion-ams/src/main`.

## Tests

- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/utils/TestParamSignatureCalculator.java`
- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/utils/TestPropertiesUtil.java`
- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/utils/TestOptimizingUtil.java`
- Run one:
  `./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestParamSignatureCalculator -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`dashboard`](../README.md), [`dashboard/controller`](../controller/README.md),
  [`authentication`](../../authentication/README.md), [`terminal`](../../terminal/README.md)
