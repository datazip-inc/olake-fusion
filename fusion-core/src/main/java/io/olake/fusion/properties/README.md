# `io.olake.fusion.properties`

String constants for catalog properties (`CatalogMetaProperties`) and table location keys
(`MetaTableProperties`).

## Role in Fusion

Constants only, compiled into both sides. Who reads them:

- `CatalogMetaProperties`: mostly the **server** (catalog registration, `CatalogBuilder`,
  `CatalogConnectionTester`, `CatalogController`, `ExternalCatalog`) and the catalog code in
  `fusion-core` (`IcebergCatalogFactory`, `CatalogUtil`, `MixedFormatCatalogUtil`,
  `table.TableProperties`).
- `MetaTableProperties`: mixed-format location keys; out of Fusion's scope (still referenced by
  `server.table.TableMetadata` and `InternalIcebergCreator`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `CatalogMetaProperties` | Catalog types, storage and auth config keys, `table-formats`, client pool keys, and prefixes for table-level properties set on the catalog. | `utils.CatalogUtil`, `fusion-ams` `server.catalog` |
| `MetaTableProperties` | Location keys `table`, `base`, `change`. Mixed-format; out of scope. | `server.table.TableMetadata` |

## How it works

**Catalog types.** `CATALOG_TYPE_GLUE = "glue"`, `CATALOG_TYPE_REST = "rest"`,
`CATALOG_TYPE_HIVE = "hive"`, `CATALOG_TYPE_CUSTOM = "custom"` are the types OLake-UI sends and the
only catalog type constants. Local tests use a `custom` catalog with
`catalog-impl=org.apache.iceberg.hadoop.HadoopCatalog` and a `file://` warehouse.
`CatalogUtil#tableFormats` reads `TABLE_FORMATS = "table-formats"` (comma-separated, unknown names
dropped) and falls back to the type: `glue`, `rest`, `custom`, `hive` → `ICEBERG`; any other type
(such as `ams`) → `IllegalArgumentException`. A Fusion
catalog sets `table-formats` to `ICEBERG` (the REST field `tableFormatList` is `["ICEBERG"]`).

**Table defaults on a catalog.** Catalog properties that start with `TABLE_PROPERTIES_PREFIX =
"table."` are table defaults: `CatalogUtil#mergeCatalogPropertiesToTable` strips the prefix and lets
the table's own properties win. OLake-UI sets `table.self-optimizing.enabled=false` and
`table.self-optimizing.quota=0.1` this way. Other prefixes: `self-optimizing.`
(`OPTIMIZE_PROPERTIES_PREFIX`), `optimize.` (deprecated), `table-expire.`, `clean-orphan-file.`,
`clean-dangling-delete-files.`, `data-expire.`, `table-trash.`, `tag.auto-create.`, `log-store.`.

**Storage and auth keys.** `storage.type` (values `S3`, `Local`), `storage.s3.region`,
`storage.s3.endpoint`; `auth.type` (values `ak/sk`, `custom`), `auth.ak_sk.access_key`,
`auth.ak_sk.secret_key`. There are no Kerberos or SIMPLE auth keys.

**Other keys.** `warehouse`, `database-filter`, `table-filter`, `clients` (default 20),
`client.pool.cache.eviction-interval-ms` (default 5 min), `ams.uri`,
`mixed-format.table-store.separator` (default `_`),
`table-properties.non-persisted.additional`, `table-properties.non-persisted.excluded`.

## State, threads and concurrency

None.

## Contracts

- All values here are stored in `catalog_metadata` in PostgreSQL and sent by OLake-UI
  (`server/internal/services/optimization/mapper.go`); renaming any of them breaks existing catalogs
  and OLake-UI.
- OLake-UI's own catalog properties (`olake_created`, `olake-catalog-type`, `olake-rest-auth-type`,
  `created-at`, `cache-enabled`) are not defined here; Fusion stores them as opaque catalog
  properties.

## Extending or changing it

Add a constant with its default (`_DEFAULT`) next to related keys. If OLake-UI or OLake-Helm must set
it, update those repos too (see CLAUDE.md section 3).

## Invariants and pitfalls

- A `table-formats` value with any format other than `ICEBERG` (for example a stored legacy
  `MIXED_ICEBERG`) is rejected by the server's `server.catalog.CatalogBuilder#checkSupported`, which
  accepts only catalogs whose formats are all `ICEBERG`. An `ams` catalog without `table-formats` is
  rejected by `CatalogUtil#tableFormats` itself.
- `CatalogController` hides `table-formats` from the properties it returns; the format list is
  exposed as `tableFormatList` instead (`ICEBERG` when the property is empty).

## Tests

Used throughout catalog tests, for example `fusion-core` `catalog/CatalogTestHelpers` and
`utils/TestMixedFormatCatalogUtil`.

## Related packages

- [`io.olake.fusion`](../README.md), [`config`](../config/README.md), `utils` (`CatalogUtil`),
  `table` (`TableProperties`)
