# `io.olake.fusion.server.persistence.converter`

MyBatis `TypeHandler`s that turn Java values (maps, lists, epoch milliseconds, enums, serialized objects) into the text, timestamp and binary columns of Fusion's PostgreSQL schema and back.

## Role in Fusion

Handlers are not registered globally; each mapper names the one it needs per column, with `typeHandler=io.olake.fusion.server.persistence.converter.<Class>` inside `#{...}` or `typeHandler = <Class>.class` in `@Result`. See [`mapper`](../mapper/README.md).

## Key classes

| Class | Java ↔ column | Write | Read | Used for (examples) |
| --- | --- | --- | --- | --- |
| `Map2StringConverter` | `Map<String,String>` ↔ text | Gson JSON; `null` → `""` | `null` → `null`, else Gson | `catalog_metadata.storage_configs/auth_configs/catalog_properties`, `table_runtime.table_config`, `table_metadata.properties`, `table_process.summary/process_parameters`, `table_blocker.properties` |
| `MapLong2StringConverter` | `Map<String,Long>` ↔ text | Gson; `null` → `""` | Gson | `optimizing_process_state.from_sequence/to_sequence` |
| `JsonObjectConverter<T>` | any bean ↔ text | Jackson `writeValueAsString` | `null`/blank → `null`; unknown properties ignored | `table_runtime.table_summary` (`TableSummary`), `task_runtime.metrics_summary`, `resource_group.properties`, `resource.properties` |
| `List2StringConverter` | `List` ↔ varchar | elements joined with `,` | split on `,`; `""` → empty list | `table_blocker.operations` |
| `Long2TsConverter` | `long` epoch ms ↔ TIMESTAMP | `0` → `NULL`, else `new Timestamp(ms)` | `NULL` → `null` | `create_time`, `finish_time`, `expiration_time`, `status_code_update_time`, task start/end times |
| `Object2ByteArrayConvert<T>` | serializable object ↔ bytea | `SerializationUtil.simpleSerialize` then `CompressUtil.gzip`; `null` → `setNull(BINARY)` on Postgres | gunzip then deserialize | `task_runtime.rewrite_output`, `optimizing_process_state.rewrite_input` |
| `TableFormatConverter` | `TableFormat` ↔ varchar | `name()`; `null` → `""` | `TableFormat.valueOf` | `table_identifier.format` |
| `TaskDescriptorTypeConverter` | `StagedTaskDescriptor` | writes nothing | always `new RewriteStageTask()` | task runtime stage (only the rewrite stage exists) |

## How it works

MyBatis calls `setParameter` / `setNonNullParameter` when binding a statement and `getResult` / `getNullableResult` when mapping a row. `JsonObjectConverter` needs its target class; MyBatis passes the property's Java type to the `Class<T>` constructor.

## State, threads and concurrency

Handlers are shared by MyBatis across threads. `JsonObjectConverter` uses one static Jackson `ObjectMapper` (and reconfigures it in each constructor); the Gson-based handlers hold a `Gson` per instance. All are stateless otherwise.

## Contracts

- Stored formats are part of the persisted data: Gson JSON objects for maps, Jackson JSON for beans, comma-joined strings for blocker operations, gzip + Fusion's `SerializationUtil` (Java serialization) for binary columns.
- `Long2TsConverter` maps `0` to SQL `NULL` (for example an unfinished `finish_time`).

## Extending or changing it

Copy `TableFormatConverter` for a new enum or `JsonObjectConverter` for a new bean column; reference the handler explicitly in each mapper statement and `@Result` that touches the column. Changing a handler's format changes how existing rows are read, so it needs a data migration (see [`persistence`](../README.md)).

## Invariants and pitfalls

- `Map2StringConverter` writes `""` for `null` but reads `""` back through Gson, which returns `null`; code must accept either.
- Values containing `,` cannot round-trip through `List2StringConverter`.
- `Object2ByteArrayConvert` uses Java serialization: renaming or changing serialized optimizer classes (`RewriteFilesInput`, `RewriteFilesOutput`) breaks reading rows written by an older build, including tasks in flight across an upgrade.
- `TableFormat.valueOf("")` throws, so a row written with a `null` format cannot be read.
- `catalog_metadata` credentials pass through `Map2StringConverter` unencrypted.

## Tests

No dedicated tests; handlers are exercised by mapper-using tests on Derby (for example `table/TestDefaultTableRuntimeStore`).

## Related packages

- [`persistence/mapper`](../mapper/README.md): where every handler is referenced.
- [`persistence`](../README.md).
