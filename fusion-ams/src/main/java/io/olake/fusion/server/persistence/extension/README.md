# `io.olake.fusion.server.persistence.extension`

A MyBatis language driver that expands a short `IN (#{name::number[]})` placeholder into a `<foreach>` over a collection parameter.

## Role in Fusion

Mapper methods opt in with `@Lang(InListExtendedLanguageDriver.class)`. Current users:

- `TableMetaMapper.selectTableIdentifiers` — `WHERE table_id IN (#{tableIds::number[]})`
- `TableProcessMapper.selectTableMaxProcessIds` — `WHERE table_id in (#{tables::number[]})`
- `OptimizingProcessMapper.selectTableQuotas` — `WHERE table_id in (#{tables::number[]})`

All are called from `table.DefaultTableManager.queryTableOptimizingInfo`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `InListExtendedLanguageDriver` | Extends `XMLLanguageDriver`; in `createSqlSource` rewrites `(#{x::number[]})` to `(<foreach collection="x" item="__item" separator="," >#{__item}</foreach>)` wrapped in `<script>`; same for `::string[]` with the item in double quotes. Logs the rewrite at INFO. | MyBatis `Configuration` |

## How it works

`createSqlSource` runs once per statement when the mapper is registered. It matches `\(#\{(\w+)::number\[]}\)` first; only if none match does it try `::string[]`. The rewritten script is parsed by the normal XML driver.

## State, threads and concurrency

Stateless after construction; patterns are compiled per instance.

## Contracts

The placeholder name must equal the `@Param` name of a `Collection` parameter.

## Extending or changing it

Use `::number[]` for numeric id lists. For other cases prefer an explicit `<script><foreach ...></script>`, as `OptimizingProcessMapper.selectOptimizeTaskMetas` does.

## Invariants and pitfalls

- An empty collection renders `IN ()`, which is a syntax error on PostgreSQL. Callers must check for empty input first (`DefaultTableManager` returns early when no runtimes match).
- `::string[]` wraps each bound parameter in double quotes (`"?"`); on PostgreSQL that is a quoted identifier, not a string, so it would not work. Nothing uses it today.
- A statement cannot mix `::number[]` and `::string[]`; only the first kind found is rewritten.

## Tests

No dedicated test. `table/TestTableManagerQuery` exercises `queryTableOptimizingInfo` on Derby.

## Related packages

- [`persistence/mapper`](../mapper/README.md), [`persistence`](../README.md).
