# `io.olake.fusion.formats.iceberg`

Iceberg implementation of Fusion's format-neutral catalog and table API: builds the Iceberg catalog
from catalog properties and wraps each loaded Iceberg table and its FileIO. Calls run as the process
user; storage credentials come from the catalog's FileIO properties.

## Role in Fusion

Used on the **server** wherever a table is loaded (`CatalogManager.loadTable`, table refresh,
planning, commit, maintenance). The `MixedTable` it produces is also placed into every
`RewriteFilesInput` and Java-serialized to the **optimizer**, so the optimizer reads and writes
files through the same wrapper.

`IcebergCatalogFactory` is registered through Java `ServiceLoader`
(`fusion-core/src/main/resources/META-INF/services/io.olake.fusion.FormatCatalogFactory`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `IcebergCatalogFactory` | `FormatCatalogFactory` for `TableFormat.ICEBERG`. Adds Iceberg init properties (`MixedFormatCatalogUtil.withIcebergCatalogInitializeProperties`), applies static Glue credentials for `glue` metastores (`StaticAwsCredentialsProvider.applyGlueCredentials`), then `CatalogUtil.buildIcebergCatalog`. | `IcebergCatalog`, `TableMetaStore` |
| `IcebergCatalog` | `FormatCatalog`: list/create/drop namespaces, list/load/drop tables, each call inside `metaStore.doAs(...)`. Wraps the catalog with `MixedFormatCatalogUtil.buildCacheCatalog` (Iceberg `CachingCatalog` only when `cache-enabled` is true and the expiration interval is positive; OLake-UI sets `cache-enabled=false`). | Iceberg `Catalog`, `SupportsNamespaces` |
| `IcebergTable` | `FusionTable<UnkeyedTable>`; `newIcebergTable(...)` builds the wrapper. `currentSnapshot()` returns an `IcebergSnapshot`. | `BasicUnkeyedTable`, `AuthenticatedFileIOs` |
| `IcebergSnapshot` | `TableSnapshot` over an Iceberg `Snapshot` (`id`, `commitTime`, `operation`, `schemaId`; `watermark()` is always `-1`). | |

## How it works

`IcebergTable.newIcebergTable(identifier, icebergTable, metaStore, catalogProperties)`:

1. `AuthenticatedFileIOs.buildAdaptIcebergFileIO(icebergTable.io())` wraps the table's FileIO in an
   `AuthenticatedFileIOAdapter` (no impersonation; see [`io`](../../io/README.md)).
2. `MixedFormatCatalogUtil.useMixedTableOperations(...)` rebuilds the `BaseTable` on
   `MixedTableOperations` (or `MixedHadoopTableOperations` for Hadoop tables), which run
   `current`, `refresh` and `commit` inside `doAs` (see [`op`](../../op/README.md)).
3. The result is wrapped in an anonymous `BasicUnkeyedTable` subclass whose `format()` returns
   `ICEBERG`. `BasicUnkeyedTable.format()` also returns `ICEBERG`, so the override is redundant,
   but keep it: the subclass (`IcebergTable$1`) is part of the Java-serialized task input stored in
   Postgres, and removing it would break existing rows. The format check
   `TableFormat.ICEBERG.equals(mixedTable.format())` in the evaluator and in
   `IcebergTableUtil.createOptimizingPlanner` relies on it.
4. `BasicUnkeyedTable.properties()` merges catalog-level `table.*` properties into the table
   properties (`mergeCatalogPropertiesToTable`). This is how catalog defaults such as
   `table.self-optimizing.enabled=false` reach the planner.
5. Write operations on the wrapper (`newRewrite`, `newRowDelta`, `newTransaction`, ...) return the
   `Mixed*` wrappers in `op`, so the optimizing commit and maintenance commits go through them.

## State, threads and concurrency

`IcebergCatalog` is shared by all threads of a catalog; Iceberg catalogs are thread-safe. An
`IcebergTable` wraps one loaded table version; callers refresh it (`table.refresh()`) when they need
the latest metadata. Without caching, every `loadTable` hits the catalog.

## Contracts

- Catalog properties come from the catalog registered in Fusion (OLake-UI builds them in its
  `mapper.go`); keys such as `cache-enabled`, `warehouse`, `io-impl` and Glue credential keys are
  read by Iceberg or by `StaticAwsCredentialsProvider`. For `hive` and `custom` catalogs without
  `io-impl`, `CatalogUtil.withIcebergCatalogInitializeProperties` sets
  `io-impl=org.apache.iceberg.io.ResolvingFileIO` (`S3FileIO` for `s3`/`s3a`/`s3n` paths,
  `HadoopFileIO` otherwise); Iceberg's `HiveCatalog` and `JdbcCatalog` would otherwise default to
  `HadoopFileIO`, which cannot read `s3://` paths.
- The wrapped table is `Serializable` and travels inside `RewriteFilesInput`; changing the wrapper's
  fields changes the task wire format (see [`optimizing`](../../optimizing/README.md#contracts)).
- `NoSuchTableException` from Iceberg is rethrown as `io.olake.fusion.NoSuchTableException`.
- Iceberg APIs (1.7.2): `CatalogUtil.buildIcebergCatalog`, `Catalog`, `SupportsNamespaces`,
  `BaseTable`, `TableOperations`.

## Extending or changing it

- New catalog types are handled by Iceberg's `CatalogUtil` through `catalog-impl` / `type`; extra
  property mapping goes in `MixedFormatCatalogUtil.withIcebergCatalogInitializeProperties`.
- Do not drop the `format()` override or the `MixedTableOperations` wrapping: the first breaks
  planning, the second runs commits without the catalog credentials.

## Invariants and pitfalls

- `listDatabases`/`createDatabase` throw `UnsupportedOperationException` when the catalog does not
  implement `SupportsNamespaces`.
- `databaseExists` lists all namespaces (first level only); nested namespaces are not supported.
- `IcebergTable.currentSnapshot()` wraps `table.currentSnapshot()` even when it is `null`
  (empty table); calling methods on that `IcebergSnapshot` throws.

## Tests

`fusion-core/src/test/java/io/olake/fusion/formats/` and `TestUnifiedCatalog`, plus every
`fusion-ams` optimizing test that loads Iceberg tables.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-core -am -Dtest=TestUnifiedCatalog -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`maintainer`](maintainer/README.md), [`utils`](utils/README.md).
- [`../../op`](../../op/README.md): operation wrappers returned by `BasicUnkeyedTable`.
- [`../../optimizing`](../../optimizing/README.md): where the wrapped table is serialized.
