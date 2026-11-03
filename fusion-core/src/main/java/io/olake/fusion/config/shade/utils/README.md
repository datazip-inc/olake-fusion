# `io.olake.fusion.config.shade.utils`

`ConfigShadeUtils`: loads the `ConfigShade` implementations, defines the two `shade.*` options, and
decrypts the sensitive entries of the server configuration map.

## Role in Fusion

**Server only.** Called once at startup from `FusionServiceContainer`'s configuration helper
(`initServiceConfig`), after `config.yaml` and environment variables are merged and before
`Configurations#fromObjectMap`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ConfigShadeUtils` | Static registry of `ConfigShade`s by identifier; options `SHADE_IDENTIFIER_OPTION` and `SHADE_SENSITIVE_KEYWORDS`; `decryptConfig(Map)`, `decryptConfig(identifier, map, sensitiveOptions, serviceConfig)`, `decryptOption(identifier, content)`. | `ConfigShade`, `config.Configurations`, `FusionServiceContainer` |
| `ConfigShadeUtils.DefaultConfigShade` | Identifier `default`; `decrypt` returns the input. | registered in the services file |

## How it works

1. The static initializer runs `ServiceLoader.load(ConfigShade.class)` and puts each shade into
   `CONFIG_SHADES` by `getIdentifier()`.
2. `decryptConfig(configMap)` builds a temporary `Configurations` from the map, reads
   `shade.identifier` (default `default`) and `shade.sensitive-keywords` (list, default
   `admin-password` and `database.password`, `;`-separated in YAML).
3. The four-argument `decryptConfig` looks up the shade; an unknown identifier throws
   `IllegalStateException("Can not find config shade: ...")`. It calls `initialize(serviceConfig)`.
   For the `default` shade it returns the map unchanged.
4. Otherwise, for each sensitive key it runs `configMap.computeIfPresent(key, decrypt)`, so it
   mutates and returns the same map. Keys are the flattened config keys, for example
   `database.password` for `database: password:` under the server section.

`decryptOption(identifier, content)` falls back to the default shade for an unknown identifier
(no exception); it is marked `@VisibleForTesting`.

## State, threads and concurrency

`CONFIG_SHADES` is a static `HashMap` filled once in the static initializer and only read afterwards.
`decryptConfig` mutates the map it is given; it is called from one thread at startup.

## Contracts

- `shade.identifier` (default `default`): `default` or `base64` built in.
- `shade.sensitive-keywords` (default `admin-password;database.password`).
- Both are documented in the server's configuration golden file through `ConfigurationsTest`.
- `local-test/config.yaml` and `dist/src/main/fusion-bin/conf/config.yaml` set both under `shade:`.

## Extending or changing it

To protect another secret, add its flattened key to `shade.sensitive-keywords` in `config.yaml` (or
change the default here and regenerate the golden file). For a new algorithm, add a `ConfigShade`
(see [`impl`](../impl/README.md)).

## Invariants and pitfalls

- Matching is by exact flattened key. A value that comes from an environment variable is decrypted
  too, because env values are merged into the map before decryption.
- An unknown `shade.identifier` stops server startup.
- The log line lists the key names, not the values.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/config/TestConfigShade.java`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am \
  -Dtest=TestConfigShade -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`config.shade`](../README.md), [`config.shade.impl`](../impl/README.md), [`config`](../../README.md)
