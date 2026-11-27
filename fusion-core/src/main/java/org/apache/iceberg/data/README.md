# `org.apache.iceberg.data` (shim in fusion-core)

`IcebergRecordWrapper`, a copy of Iceberg's `InternalRecordWrapper` that also converts UUID values.

## Role in Fusion

`io.olake.fusion.io.writer.GenericIcebergPartitionedFanoutWriter.partition(Record)` wraps each row in it before computing the `PartitionKey`, so that date, time, timestamp, fixed and UUID values are in Iceberg's internal representation when partition transforms run.

Runs in: **optimizer** only.

## Why it lives in this package

Unlike [`org.apache.iceberg`](../README.md), this class uses only public Iceberg API (`StructLike`, `Types`, `DateTimeUtil`, `UUIDUtil`). It sits in `org.apache.iceberg.data` to stay next to the class it copies, `InternalRecordWrapper`. Its javadoc says it is a temporary fix for UUID handling (Amoro PR 3797) until Iceberg PR 14208 is released, after which it should be removed.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `IcebergRecordWrapper` | `StructLike` view over a record. Per field: DATE → days, TIME → micros, TIMESTAMP(TZ) → micros, FIXED → `ByteBuffer`, UUID given as `byte[]` → `java.util.UUID` (via `UUIDUtil.convert`), nested STRUCT → nested wrapper. `wrap(record)` reuses the instance; `copyFor(record)` creates a new one. `set` throws. | Iceberg `DateTimeUtil`, `UUIDUtil` |

## State, threads and concurrency

`wrap` mutates the wrapper; one instance per writer, single-threaded.

## Contracts

None.

## Extending or changing it

On an Iceberg upgrade, check whether `InternalRecordWrapper` at the new tag handles UUID (`git -C <iceberg> show apache-iceberg-<version>:data/src/main/java/org/apache/iceberg/data/InternalRecordWrapper.java`). If it does, replace this class with it in `GenericIcebergPartitionedFanoutWriter` and delete it.

## Invariants and pitfalls

- If Iceberg adds a class with the same fully qualified name, the two clash on the classpath.
- Keep the conversions in sync with `InternalRecordWrapper`; a mismatch puts rows in the wrong partition.

## Tests

No dedicated test; covered through writer tests such as `fusion-core/src/test/java/io/olake/fusion/io/TestIcebergCombinedReaderVariousTypes.java` (uses `IcebergDataTestHelpers`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestIcebergCombinedReaderVariousTypes -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md), [`../../../../io/olake/fusion/io/writer`](../../../../io/olake/fusion/io/writer/README.md)
