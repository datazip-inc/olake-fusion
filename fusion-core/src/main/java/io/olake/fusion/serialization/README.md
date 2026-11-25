# `io.olake.fusion.serialization`

Object serializers behind `utils.SerializationUtil`: Java serialization (`SimpleSerializer`) and
Kryo (`KryoSerializer`, plus `JavaSerializer`, which despite its name also uses Kryo). Both can read
data written before the rename from Amoro (`org.apache.amoro.*`) to Fusion (`io.olake.fusion.*`).

## Role in Fusion

**Both, and it is a wire and storage format.**

- **Task input and output (server ↔ optimizer).** `process.StagedTaskDescriptor` sets
  `OptimizingTask.taskInput` with `SerializationUtil#simpleSerialize` (server). The optimizer
  (`SparkOptimizerExecutor`, `OptimizerExecutor`) reads it with `#simpleDeserialize` and returns task
  output the same way; `optimizing.RewriteStageTask` deserializes the output in the server.
- **PostgreSQL.** `server.persistence.converter.Object2ByteArrayConvert` and
  `server.persistence.TaskFilesPersistence` store gzip-compressed `simpleSerialize` bytes and read
  them back with `simpleDeserialize`.
- **Optimizer spill and hashing.** `utils.map.RocksDBBackend`, `StructLikeWrapperSerializer`,
  `SimpleSpillableMap`, `StructLikeSpillableMap` and `io.reader.StructLikeFunnel` use Kryo (local to
  one process).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ResourceSerde<R>` | `serializeResource(R)` → `byte[]`, `deserializeResource(byte[])` → `R`. | all serializers |
| `SimpleSerializer<R>` | `ObjectOutputStream` / `LegacyAwareObjectInputStream`. `null` input → `null`; errors become `IllegalArgumentException`. | `SerializationUtil#simpleSerialize`, `#simpleDeserialize` |
| `LegacyAwareObjectInputStream` | `readClassDescriptor` maps old class names with `LegacyClassNames#toCurrent` and returns the current class's local descriptor. | `utils.LegacyClassNames` |
| `KryoSerializer<R>` | Kryo with a per-thread instance (`ThreadLocal<KryoSerializerInstance>`), no registration required, Objenesis fallback instantiation, a `Utf8` serializer, and `LegacyAwareClassResolver`. `null` input to deserialize throws `NullPointerException`. | `SerializationUtil#kryoSerialize`, `#kryoDeserialize` |
| `JavaSerializer<R extends Serializable>` | Singleton `INSTANT` that delegates to a `KryoSerializer`; `null` input → `null`. | `SimpleSpillableMap`, `SerializationUtil#createJavaSimpleSerializer` |

## How it works

- **Java path.** `SerializationUtil#simpleSerialize(obj)` wraps `SimpleSerializer#serializeResource`
  in a `ByteBuffer` (the thrift `binary` field). Reading uses `LegacyAwareObjectInputStream`: if a
  class name in the stream starts with `org.apache.amoro.` (but not `org.apache.amoro.shade.`), it is
  mapped to `io.olake.fusion.` plus the same package, with `Amoro*` simple names renamed by
  `LegacyClassNames.RENAMED_CLASSES` (for example `AmoroRuntimeException` → `FusionRuntimeException`).
  Array descriptors are handled. The local class descriptor replaces the stream's, because the
  renamed class has a different default `serialVersionUID`.
- **Kryo path.** Each thread has its own `Kryo` and a reusable 1 MB `ByteArrayOutputStream`.
  `serialize` calls `kryo.reset()` and `writeClassAndObject`. `LegacyAwareClassResolver#getTypeByName`
  falls back to `LegacyClassNames#toCurrent` when the written name is not found.

## State, threads and concurrency

- `KryoSerializer` keeps one Kryo instance per thread; a `KryoSerializer` object can be shared.
- `SimpleSerializer` is stateless.
- The Kryo class loader is the thread context class loader at the time the thread first uses it.

## Contracts

- **Java serialization of task input and output is the server–optimizer contract.** Both sides must
  have compatible versions of every class in the object graph (for example
  `optimizing.RewriteFilesInput`, which pins `serialVersionUID = -696610318564333923L`, and the
  Iceberg classes it contains, at Iceberg 1.7.2).
- Persisted blobs in PostgreSQL are gzip + Java serialization; old rows may contain `org.apache.amoro`
  class names.
- Kryo bytes are not persisted across processes and not sent over thrift.

## Extending or changing it

- A class sent in task input or output must be `Serializable` and should declare
  `serialVersionUID`. Add fields in a compatible way (new fields get default values on old data).
- If another class is renamed, add it to `LegacyClassNames.RENAMED_CLASSES` so old data still loads.

## Invariants and pitfalls

- **Rolling upgrades.** A server and an optimizer on different Fusion versions exchange Java-serialized
  objects. Renaming, moving or changing a field type of a class in the task graph, or a class without
  an explicit `serialVersionUID`, breaks deserialization on the older side. In-flight processes are
  also read back from PostgreSQL after a server restart.
- `LegacyAwareObjectInputStream` maps only `org.apache.amoro.*`; other renames need explicit handling.
- Java deserialization of untrusted bytes is unsafe; only server and optimizer exchange these bytes.
- `JavaSerializer` is Kryo, not Java serialization.

## Tests

`fusion-core/src/test/java/io/olake/fusion/utils/TestLegacySerializedClassNames.java` (Java and Kryo
data with legacy names); `utils/map/TestSimpleSpilledMap`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am \
  -Dtest=TestLegacySerializedClassNames -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion`](../README.md), `utils` (`SerializationUtil`, `LegacyClassNames`, `map`),
  `optimizing` (`RewriteFilesInput`, `RewriteStageTask`), `process` (`StagedTaskDescriptor`)
