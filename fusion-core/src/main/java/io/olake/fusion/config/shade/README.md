# `io.olake.fusion.config.shade`

The `ConfigShade` SPI: a pluggable way to decrypt sensitive values (passwords) in the server
configuration before the server uses them.

## Role in Fusion

**Server only.** `fusion-ams`'s `FusionServiceContainer` calls `ConfigShadeUtils#decryptConfig` on the
flattened `config.yaml` + environment map at startup. The optimizer does not use it.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ConfigShade` | SPI: `getIdentifier()` (the name used in `shade.identifier`), `decrypt(String)`, and a default no-op `initialize(Configurations)` that can read the service config (for example a key file path). | `utils.ConfigShadeUtils`, `impl.Base64ConfigShade`, `ConfigShadeUtils.DefaultConfigShade` |

## How it works

Implementations are found with `java.util.ServiceLoader` through
`META-INF/services/io.olake.fusion.config.shade.ConfigShade`, which lists
`io.olake.fusion.config.shade.utils.ConfigShadeUtils$DefaultConfigShade` and
`io.olake.fusion.config.shade.impl.Base64ConfigShade`. `ConfigShadeUtils` picks one by
`getIdentifier()`, calls `initialize(serviceConfig)` and then `decrypt` on each sensitive value. See
[`utils`](utils/README.md).

## State, threads and concurrency

Implementations are created once by `ServiceLoader` and used at startup from one thread.

## Contracts

- The identifier string is what operators write in `shade.identifier`.
- The services file is the registration contract.

## Extending or changing it

Implement `ConfigShade`, return a unique identifier, and add the class name to the services file
(in `fusion-core` or in a jar on the server's classpath). Override `initialize` if the shade needs
settings from the service config.

## Invariants and pitfalls

- Two implementations with the same identifier: the one loaded last replaces the other in
  `ConfigShadeUtils`'s map.
- `decrypt` receives `value.toString()`; non-string YAML values arrive as their string form.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/config/TestConfigShade.java`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am \
  -Dtest=TestConfigShade -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`impl`](impl/README.md), [`utils`](utils/README.md), [`config`](../README.md)
