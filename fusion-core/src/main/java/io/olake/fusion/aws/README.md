# `io.olake.fusion.aws`

One AWS SDK v2 credentials provider, `StaticAwsCredentialsProvider`. It lets the Iceberg Glue
client use the same static keys that the catalog sets for S3 (`s3.access-key-id` /
`s3.secret-access-key`).

## Role in Fusion

The code is in `fusion-core`, so it is on both classpaths. It is called from the **server** when it
builds a Glue catalog:

- `formats.iceberg.IcebergCatalogFactory#create`, when the metastore type is `glue`
  (`CatalogMetaProperties.CATALOG_TYPE_GLUE`, compared ignoring case).
- `server.catalog.CatalogConnectionTester` (the connection test run when a catalog is registered).
- `server.terminal.TerminalManager` (the terminal API that OLake-UI uses for
  `ALTER TABLE ... SET TBLPROPERTIES`).

Iceberg loads the class by name, through reflection, wherever an AWS client is built from these
catalog properties.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `StaticAwsCredentialsProvider` | An `AwsCredentialsProvider` that always returns one `AwsBasicCredentials`. `create(Map)` builds it from `access-key-id` and `secret-access-key`. `applyGlueCredentials(Map)` copies the S3 keys into `client.credentials-provider.*` properties. | Iceberg `AwsClientProperties`, `S3FileIOProperties`, `IcebergCatalogFactory` |

## How it works

`applyGlueCredentials(properties)`:

1. Returns `null` for `null` input. Returns the input unchanged if `S3FileIOProperties.ACCESS_KEY_ID`
   (`s3.access-key-id`) or `S3FileIOProperties.SECRET_ACCESS_KEY` (`s3.secret-access-key`) is missing.
2. Otherwise returns a copy with three more entries:
   - `client.credentials-provider` (`AwsClientProperties.CLIENT_CREDENTIALS_PROVIDER`) =
     `io.olake.fusion.aws.StaticAwsCredentialsProvider`
   - `client.credentials-provider.access-key-id` = the S3 access key
   - `client.credentials-provider.secret-access-key` = the S3 secret key

In Iceberg 1.7.2, `AwsClientProperties` loads the named class and calls its static `create(Map)`
with the properties under the `client.credentials-provider.` prefix (prefix removed). That is why the
keys in `create` are `ACCESS_KEY_ID = "access-key-id"` and `SECRET_ACCESS_KEY = "secret-access-key"`.
`create` throws `IllegalArgumentException` if either is missing.

Iceberg's S3 client is not affected when both S3 keys are set: in 1.7.2,
`AwsClientProperties#credentialsProvider(accessKeyId, secretAccessKey, sessionToken)` uses the S3 keys
before it looks at `client.credentials-provider`. The provider matters for the Glue client and other
clients built through `AwsClientFactories`, which otherwise fall back to the default AWS credential
chain.

## State, threads and concurrency

Stateless helper plus an immutable provider; thread safe.

## Contracts

- The fully qualified class name `io.olake.fusion.aws.StaticAwsCredentialsProvider` is written into
  catalog properties and loaded by name, so it is a contract with every process that builds AWS
  clients from those properties.
- The static `create(Map<String, String>)` signature and the keys `access-key-id` /
  `secret-access-key` are what Iceberg calls.

## Extending or changing it

Keep the class name, package and `create(Map)` signature. To support session tokens, add a key and
return `AwsSessionCredentials`, and set that key in `applyGlueCredentials`.

## Invariants and pitfalls

- Renaming or moving the class breaks any process whose classpath does not have the new name while
  it still receives properties with the old one (for example a mixed-version rollout).
- Only static keys are supported; no session token, no refresh.
- `applyGlueCredentials` puts the secret key into a second property; anything that logs or returns
  catalog properties now shows it twice.

## Tests

No unit tests in the repo. Check the change through the Glue catalog connection test
(`CatalogConnectionTester`) on a server run from source.

## Related packages

- [`io.olake.fusion`](../README.md)
- [`properties`](../properties/README.md) (`CatalogMetaProperties.CATALOG_TYPE_GLUE`)
- `formats/iceberg` (`IcebergCatalogFactory`), `fusion-ams` `server.catalog`, `server.terminal`
