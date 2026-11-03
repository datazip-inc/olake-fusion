# `io.olake.fusion.server.authentication`

Pluggable password and bearer-token authentication for Fusion's HTTP API: the provider interfaces,
the credential types, the default admin-password provider, and the factory that loads providers by
class name and reads credentials from a request.

## Role in Fusion

- `LoginController` (in [`dashboard/controller`](../dashboard/controller/README.md)) uses a
  `PasswdAuthenticationProvider` for `POST /api/ams/v1/login`. OLake-UI's Go backend logs in this
  way once (`client.go`, user and password from its `USERNAME`/`PASSWORD` env), then creates an API
  token.
- `DashboardServer.preHandleRequest` (in [`dashboard`](../dashboard/README.md)) uses a provider for
  REST calls only when `http-server.rest-auth-type` is `basic` or `jwt`. With the default `token`
  (and in Fusion's `config.yaml` files), REST calls are checked by API-token signature in
  `ApiTokenController.checkApiToken`, not by this package.
- The Iceberg REST catalog service and the optimizer do not use this package.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `PasswdAuthenticationProvider` | Interface: `Principal authenticate(PasswordCredential)`; throws `SignatureCheckException` on failure | `LoginController`, `DashboardServer` |
| `TokenAuthenticationProvider` | Interface: `Principal authenticate(TokenCredential)` for bearer (JWT) tokens | `DashboardServer` |
| `DefaultPasswdAuthenticationProvider` | Accepts exactly `admin-username` / `admin-password` from config (defaults `admin` / `admin`); returns a `BasicPrincipal` | `FusionManagementConf.ADMIN_USERNAME`, `ADMIN_PASSWORD` |
| `HttpAuthenticationFactory` | `getPasswordAuthenticationProvider` / `getBearerAuthenticationProvider` build a provider from a class name via `DynConstructors` (constructor taking `Configurations`, else no-arg); `getPasswordCredential` reads HTTP Basic credentials; `getBearerTokenCredential` reads `Authorization: Bearer <token>` | Javalin `Context`, `DynConstructors` |
| `PasswordCredential`, `DefaultPasswordCredential` | Username, password, `extraInfo` | |
| `TokenCredential`, `DefaultTokenCredential` | Token, `extraInfo`; `CLIENT_IP_KEY = "clientIp"` | |
| `BasicPrincipal` | Named `Principal` | |

## How it works

1. **Login.** `LoginController` is built with the class from `http-server.login-auth-provider`
   (default `DefaultPasswdAuthenticationProvider`). `login` builds a `DefaultPasswordCredential`
   from body fields `user` and `password` and calls `authenticate`. On success it stores
   `SessionInfo` as session attribute `user`; on failure it throws, which becomes a 500 envelope
   `"invalid user ... or password! ..."`.
2. **REST with `basic`.** `preHandleRequest` calls
   `getPasswordCredential(ctx, proxyClientIpHeader)` and the provider from
   `http-server.auth-basic-provider`.
3. **REST with `jwt`.** It calls `getBearerTokenCredential` and the provider from
   `http-server.auth-jwt-provider` (no default). Fusion ships no `TokenAuthenticationProvider`
   implementation; only the test class `UserDefinedTokenAuthenticationProviderImpl` exists.
4. `extraInfo` carries the client IP: header `http-server.proxy-client-ip-header`
   (default `X-Real-IP`) if present, else `ctx.ip()`.

## State, threads and concurrency

Providers are created once, at `DashboardServer` and `LoginController` construction, and shared by
all request threads. `DefaultPasswdAuthenticationProvider` only reads two final-after-construction
strings.

## Contracts

- Config keys (under `ams:` in `config.yaml`): `admin-username`, `admin-password`,
  `http-server.login-auth-provider`, `http-server.rest-auth-type`,
  `http-server.auth-basic-provider`, `http-server.auth-jwt-provider`,
  `http-server.proxy-client-ip-header`. OLake-UI's login depends on `admin-username` and
  `admin-password` matching its `USERNAME`/`PASSWORD`; check OLake-Helm and
  `docker-compose-v1.yml` before renaming them.
- A custom provider must implement the interface and have a public constructor taking
  `Configurations` or no arguments; otherwise the factory throws
  `IllegalStateException("<class> must extend of <interface>")` at server start.

## Extending or changing it

Implement `PasswdAuthenticationProvider` (or `TokenAuthenticationProvider`), put the jar on the
server classpath, and set its class name in `http-server.login-auth-provider` or
`http-server.auth-basic-provider` / `http-server.auth-jwt-provider`. Copy
`DefaultPasswdAuthenticationProvider`.

## Invariants and pitfalls

- The default admin password is `admin`; a deployment that does not set `admin-password` is open
  to anyone who can reach the port.
- `DefaultPasswdAuthenticationProvider` compares with `String.equals`, not a constant-time compare.
- Changing `http-server.rest-auth-type` to `basic` or `jwt` turns off API-token checks; OLake-UI
  only sends `apiKey`/`signature`, so its calls would fail.
- None of this applies to requests that carry a `token` query parameter: the `before` filter in
  `FusionServiceContainer` routes them to `CommonUtil.checkSinglePageToken` instead (see
  [`dashboard/utils`](../dashboard/utils/README.md)).
- LDAP authentication was removed from this package; LDAP is out of Fusion's scope.

## Tests

- `fusion-ams/src/test/java/io/olake/fusion/server/authentication/HttpAuthenticationFactoryTest.java`
  (with `UserDefinedTokenAuthenticationProviderImpl`)
- `fusion-ams/src/test/java/io/olake/fusion/server/dashboard/TestLoginAndApiToken.java`
- Run one:
  `./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=HttpAuthenticationFactoryTest -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`dashboard`](../dashboard/README.md), [`dashboard/controller`](../dashboard/controller/README.md),
  [`dashboard/utils`](../dashboard/utils/README.md)
