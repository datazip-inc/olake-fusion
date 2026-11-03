# `io.olake.fusion.config.shade.impl`

Built-in `ConfigShade` implementations. There is one: `Base64ConfigShade`.

## Role in Fusion

**Server only**, at startup, when `config.yaml` sets `shade.identifier: base64`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `Base64ConfigShade` | Identifier `base64`. `decrypt` decodes the value with `java.util.Base64.getDecoder()` and builds a `String` with the platform default charset. | `config.shade.utils.ConfigShadeUtils` |

## How it works

Registered in `META-INF/services/io.olake.fusion.config.shade.ConfigShade`. When
`shade.identifier` is `base64`, `ConfigShadeUtils#decryptConfig` replaces each key listed in
`shade.sensitive-keywords` (default `admin-password;database.password`) with its decoded value.

## State, threads and concurrency

Stateless; the decoder is a static thread-safe instance.

## Contracts

- Identifier `base64`.
- Values must be standard Base64 (not URL-safe); invalid input throws `IllegalArgumentException`
  and stops startup.

## Extending or changing it

Add another class implementing `ConfigShade` here and list it in the services file. Base64 is
encoding, not encryption; a real cipher needs a key, read in `initialize`.

## Invariants and pitfalls

- Decoding uses the JVM default charset; non-ASCII secrets depend on it.
- Base64 hides values from casual reading only.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/config/TestConfigShade.java`
(`testDecryptServiceConfigWithBase64Shade`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am \
  -Dtest=TestConfigShade -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`config.shade`](../README.md), [`config.shade.utils`](../utils/README.md)
