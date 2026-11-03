# `io.olake.fusion.server.dashboard.component.reverser`

Rebuilds a table's DDL history ("operations") for the table page: it turns each Iceberg
`metadata.json` version into a schema and property snapshot that `DDLReverser` diffs into
Spark SQL statements.

## Role in Fusion

- Used only by `MixedAndIcebergTableDescriptor.getTableOperations`, which serves
  `GET /api/ams/v1/tables/catalogs/{catalog}/dbs/{db}/tables/{table}/operations`
  (`TableController.getTableOperations`). OLake-UI does not call this route.
- Calls the Iceberg API (`TableMetadataParser`, `TypeUtil`) and Spark's type classes
  (`org.apache.spark.sql.types.*`) only to print type names.
- `DDLReverser` and the `TableMetaExtract` interface live in `fusion-core`
  (`io.olake.fusion.table.descriptor`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `IcebergTableMetaExtract` | Implements `TableMetaExtract<Table>`. `extractTable` reads every file in `operations().current().previousFiles()` plus the current metadata, and returns one `InternalTableMeta(lastUpdatedMillis, schema, properties)` per version | `TableMetadataParser`, `HasTableOperations` |
| `IcebergTypeToSparkType` | `TypeUtil.SchemaVisitor<DataType>` that maps Iceberg types to Spark `DataType`s, used to print type names in `catalogString()` form | Spark `DataType` singletons |

## How it works

1. `MixedAndIcebergTableDescriptor.getTableOperations` builds
   `new DDLReverser<>(new IcebergTableMetaExtract())` and calls `reverse(table, id)`.
2. `IcebergTableMetaExtract.extractTable` flattens each schema with `transform`: every struct
   field, list `element`, and map `key`/`value` becomes an `InternalSchema(id, parentId,
   dotted name, Spark type string, doc, required)`.
3. `DDLReverser.reverse` compares each version with the previous one. Property changes become
   `UPDATE_PROPERTIES` statements and column changes `UPDATE_SCHEMA` statements, written by
   `SparkMetadataChangeHandler`, each stamped with the newer version's time.
4. `TableController` reverses the list (newest first) and pages it as `TableOperation`.

## State, threads and concurrency

Stateless. `IcebergTypeToSparkType` keeps no fields; a new extractor is created per request.

## Contracts

- Output strings are Spark SQL text built from these type names; the UI shows them as-is.
- Type mapping: `UUID` to `string`, `FIXED` and `BINARY` to `binary`, `TIMESTAMP` (with or without
  zone) to `timestamp`. `TIME` throws `UnsupportedOperationException`, so a table with a `time`
  column makes the operations route fail.

## Extending or changing it

- To support another type, add a `case` in `IcebergTypeToSparkType.primitive`.
- Keep Iceberg calls valid for Iceberg `1.7.2` (`iceberg.version` in the root `pom.xml`).

## Invariants and pitfalls

- `extractTable` reads one `metadata.json` per previous version listed in the current metadata,
  so the request cost grows with `write.metadata.previous-versions-max` and the storage latency.
  Every read goes through `table.io()`.
- Only versions still listed in `previousFiles()` are visible; older history is gone.

## Tests

No test targets this package directly. Exercise it through
`fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestIcebergServerTableDescriptor.java`:
`./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestIcebergServerTableDescriptor -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`dashboard`](../../README.md) (`MixedAndIcebergTableDescriptor`)
- [`dashboard/controller`](../../controller/README.md) (`TableController.getTableOperations`)
