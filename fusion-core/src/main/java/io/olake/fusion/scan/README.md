# `io.olake.fusion.scan`

`TableEntriesScan`, a reader of Iceberg manifest entries (the `ENTRIES` / `ALL_ENTRIES` metadata
tables) that returns each file with its snapshot id, sequence number and status, plus a few scan-task
types.

## Role in Fusion

Server side, for maintenance, not for compaction planning (planning uses
[`optimizing/scan`](../optimizing/scan/README.md)). Callers:

- `formats/iceberg/utils/IcebergTableUtil.getAllContentFilePath` (orphan cleaning, `ALL_ENTRIES`) and
  `getDanglingDeleteFiles` (current snapshot entries);
- `io.olake.fusion.server.utils.IcebergTableUtil` in `fusion-ams` (same helpers).

`CombinedIcebergScanTask` is referenced by `GenericCombinedIcebergDataReader` in its Javadoc only.
`MixedFileScanTask` is a mixed-format interface (a `FileScanTask` over a `PrimaryKeyedFile`) and out
of scope; `io/reader/DataReaderCommon` still checks for it with `instanceof`, which is never true for
Iceberg `FileScanTask`s.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TableEntriesScan` (+ `Builder`) | Builds a metadata table (`MetadataTableUtils.createMetadataTableInstance`) and turns its rows into `IcebergFileEntry(snapshotId, sequence, status, contentFile)`. | `IcebergFileEntry`, `ManifestEntryFields` |
| `CombinedIcebergScanTask` | Data files, delete files, spec and partition of one combined read. | |
| `MixedFileScanTask` | Mixed-format scan task interface; out of scope. | `data.PrimaryKeyedFile` |

## How it works

Builder options: `withDataFilter(expr)`, `withAliveEntry(bool)` (default `true`: skip `DELETED`
entries), `includeFileContent(...)`, `useSnapshot(id)`, `includeColumnStats()`, `fromSequence(seq)`,
`project(schema)`, `allEntries()` (switch from `ENTRIES` to `ALL_ENTRIES`).

`entries()`:

1. Plans the metadata table scan (with snapshot and projection if set) and concatenates the rows of
   each `DataTask`.
2. Per row: drops entries with `sequence_number < fromSequence`; drops `DELETED` entries when
   `aliveEntry`; keeps only the requested `FileContent`s.
3. Rebuilds a `DataFile` (`DataFiles.builder`) or `DeleteFile` (`FileMetadata.deleteFileBuilder`,
   equality or position) with path, size, record count, format, partition and, if a filter or column
   stats are requested, metrics. The spec comes from the entry's `spec_id`, falling back to the
   current spec.
4. Applies an `InclusiveMetricsEvaluator` for the data filter (always true without a filter); strips
   stats again unless `includeColumnStats()`.
5. Returns a `CloseableIterable` filtered of `null`s.

## State, threads and concurrency

The scan caches the metadata table, the field index maps and the evaluator lazily; one instance per
use, not thread-safe. The caller must close the returned iterable.

## Contracts

- Reads Iceberg's manifest entry schema by field **name** (`status`, `snapshot_id`,
  `sequence_number`, `data_file`, and `DataFile` field names). Any rename in a newer Iceberg breaks
  it; check when upgrading from 1.7.2.
- Rebuilt files carry no `equalityFieldIds`, `keyMetadata`, `sortOrderId` or split offsets; use them
  for paths, sizes and counts, not for commits that need full metadata.

## Extending or changing it

Add options through the `Builder`; keep `aliveEntry=true` as the default because maintenance relies
on it.

## Invariants and pitfalls

- With `allEntries()`, the same file can appear many times (once per manifest that lists it);
  callers collect into sets.
- The metrics evaluator uses `table.spec().schema()` (current schema).
- Missing `spec_id` falls back to the current spec with an info log (its format string uses `%S`,
  so the path is not interpolated).

## Tests

Covered through maintenance tests in `fusion-ams/src/test/java/io/olake/fusion/server/optimizing/maintainer/`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestOrphanFileCleanIceberg -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../formats/iceberg/utils`](../formats/iceberg/utils/README.md): main caller.
- [`../optimizing/scan`](../optimizing/scan/README.md): the planner's scan.
