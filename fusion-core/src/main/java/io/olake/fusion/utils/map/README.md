# `io.olake.fusion.utils.map`

Maps keyed by Iceberg `StructLike` that live in memory or spill to a local RocksDB when they grow too large. They hold equality-delete keys during compaction.

## Role in Fusion

`io.reader.CombinedDeleteFilter` stores every equality-delete key of a task in a `StructLikeBaseMap<Long>` (key → max delete sequence number); `io.reader.Deletes.toEqualitySet` uses `StructLikeSet` on top of these maps. `StructLikeCollections` decides which implementation is used, from task properties (`TaskProperties.getStructLikeCollections`: `extend-disk-storage`, memory size default 512 MB, disk path).

Runs in: **optimizer**.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `StructLikeCollections` | Factory: `createStructLikeMap(type)` and `createStructLikeSet(type)` return in-memory or spillable versions. `DEFAULT` = in-memory. Serializable. | `utils.StructLikeSet` |
| `StructLikeBaseMap<T>` | `SimpleMap<StructLike, T>` that wraps keys in Iceberg `StructLikeWrapper` (per-thread wrapper for lookups, a copy for inserts). | Iceberg `StructLikeWrapper` |
| `StructLikeMemoryMap<T>` | Backed by a `HashMap<StructLikeWrapper, T>`. | — |
| `StructLikeSpillableMap<T>` | Backed by `SimpleSpillableMap` with `StructLikeWrapperSerializer` (Kryo) for keys and Java serialization for values. | `SimpleSpillableMap` |
| `SimpleSpillableMap<K,T>` | In-memory `HashMap` until the estimated size reaches `maxInMemorySizeInBytes`, then new keys go to a RocksDB column family (`SimpleSpilledMap`). | `RocksDBBackend`, `SizeEstimator` |
| `RocksDBBackend` | One RocksDB instance **per thread** (`ThreadLocal`) under `<baseDir or java.io.tmpdir>/<uuid>`, column families added and dropped per map, deleted on `close()` or JVM shutdown hook. | `LocalFileUtil`, `SerializationUtil` |
| `SimpleMap`, `SizeEstimator`, `DefaultSizeEstimator`, `StructLikeWrapperSizeEstimator`, `StructLikeWrapperSerializer` | Interfaces and helpers. `StructLikeWrapperSerializer.StructLikeCopy` is an immutable struct copy, also used by `io.reader.StructLikeFunnel`. | Lucene `RamUsageEstimator` |

## How it works

`SimpleSpillableMap.put` estimates the payload size from the first entry and refreshes the estimate every 200 puts (`RECORDS_TO_SKIP_FOR_ESTIMATING`), weighted 0.9/0.1. While `currentInMemoryMapSize < maxInMemorySizeInBytes` new keys go to memory; afterwards to disk. Updates to keys already in memory stay in memory. `get` checks memory, then disk. `close()` drops the column family.

## State, threads and concurrency

Not thread-safe. A map must be used by the thread that created it, because its `SimpleSpilledMap` holds the calling thread's `RocksDBBackend`.

## Contracts

Spilled keys are Kryo bytes of `StructLikeCopy`; values are Java-serialized. Both are local and temporary; nothing is persisted across processes.

## Extending or changing it

Implement `StructLikeBaseMap.getInternalMap()` for a new backend and wire it in `StructLikeCollections`.

## Invariants and pitfalls

- Always close maps (`CombinedDeleteFilter.close()` does it through `CloseablePredicate`); otherwise column families and disk space stay until JVM exit.
- `RocksDBBackend.getOrCreateInstance(dir)` ignores `dir` if the thread already has an open instance, and every new instance registers another shutdown hook.
- `StructLikeWrapperSizeEstimator.sizeOf` casts any array value to `Object[]`; a `byte[]` value (Iceberg FIXED in generic records) throws `ClassCastException` when spilling is enabled and an equality-delete key contains a FIXED column.
- `SimpleSpillableMap.get` uses `Optional.orElse(...)`, so the disk lookup runs even on a memory hit.
- Disk spill needs a writable local directory on Spark executors (`java.io.tmpdir` by default).

## Tests

`fusion-core/src/test/java/io/olake/fusion/utils/map/`: `TestRocksDBBackend`, `TestSimpleSpillableMap`, `TestSimpleSpilledMap`, `TestStructLikeMap`, `TestStructLikeWrapperSizeEstimator`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestSimpleSpillableMap -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md), [`../../io/reader`](../../io/reader/README.md)
