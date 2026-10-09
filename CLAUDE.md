<!--
 - Licensed to the Apache Software Foundation (ASF) under one
 - or more contributor license agreements.  See the NOTICE file
 - distributed with this work for additional information
 - regarding copyright ownership.  The ASF licenses this file
 - to you under the Apache License, Version 2.0 (the
 - "License"); you may not use this file except in compliance
 - with the License.  You may obtain a copy of the License at
 - 
 -     http://www.apache.org/licenses/LICENSE-2.0
 - 
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and 
 - limitations under the License.
 -
 - Modified by Datazip Inc. in 2026
-->

# OLake-Fusion — Agent Instructions

OLake-Fusion is a fork of Apache Amoro (Iceberg compaction engine written in Java). After fork project got renamed to Fusion. At some places in code it still mentions Amoro.

## 1. Scope

Fusion is a scoped-down fork: it does not need the full Amoro project. Fusion is compaction for Iceberg tables.

Fusion has exactly **one optimizer group and one optimizer**. There is no multi-optimizer concept: do not add code, config or tests for several optimizer groups or several optimizers. In helm deployments, the optimizer group name is `spark-container` (OLake-UI's `OPTIMIZATION_GROUP`); for local debugging, it is the `local` group
from section 8.

**In scope:** Iceberg tables only, in catalogs set up for Iceberg tables alone. In Fusion's catalog config this means `tableFormatList` is `["ICEBERG"]`.

These are the modules that usually matter, but other sometimes other modules needs to be checked as well.

| Module | Runs in | Contains |
| --- | --- | --- |
| `amoro-ams` | Server | Scheduling, commit, table runtime, REST API, etc. |
| `amoro-format-iceberg` | Server and optimizer | Evaluators and planners (server), rewrite executors (optimizer) |
| `amoro-common` | Both | Shared types and config |
| `amoro-optimizer/amoro-optimizer-common`, `-standalone`, `-spark` | Optimizer | Shared optimizer code, the local optimizer, the production optimizer |

**Compaction runs on the Spark optimizer** (`amoro-optimizer-spark`, image `olakego/fusion-spark`)
on Kubernetes. The local standalone optimizer is only for local debugging.

**Out of scope unless asked.** Do not read, fix or refactor these. If a change to shared code also
affects them, mention it in one line and move on.

- Mixed-format tables (mixed-iceberg, mixed-hive), Hive, Paimon and Hudi tables, and the modules
  that exist only for them: `amoro-format-mixed/*`, `amoro-format-hudi`, `amoro-format-paimon`.
- The Flink optimizer, `amoro-optimizer/amoro-optimizer-flink`. Fusion does not use it. Never use
  it to run or test compaction, and do not suggest it.
- Amoro's own web UI, `amoro-web` (served by Fusion on port 1630). It is not the product UI;
  OLake-UI is. Ask before changing anything there.

## 2. Deployment

Fusion is deployed together with OLake-UI, in one of two modes:

- **Docker:** OLake-UI's `docker-compose-v1.yml`. The Fusion services are under the `fusion`
  profile and need `ENABLE_OPTIMIZATION=true`. Fusion runs as a container, and the Spark optimizer
  runs on a local Kind cluster that the stack creates.
- **Kubernetes:** OLake-Helm's chart (`helm/olake`). Fusion's resources are in
  `templates/fusion/` and are switched on with `fusion.enabled` (default `false`).

`local-test/` in this repo is only for manual testing, not a product deployment.

## 3. Related repos

| Repo | Local path | Role |
| --- | --- | --- |
| OLake-UI | `<path>` | Product UI for Fusion. Its Go backend sits between the UI and Fusion: Its `docker-compose-v1.yml` is also the Docker deployment of Fusion. |
| OLake-Helm | `<path>` | Helm deployment of Fusion |
| Iceberg | `<path>` | Apache Iceberg Java source |
| OLake | `<path>` | Ingestion; writes the Iceberg tables Fusion maintains |

- If you change a Fusion REST endpoint's path, request or response, grep OLake-UI for its callers
  and report what would break.
- If you add or rename a config key, port or env var, check OLake-Helm and OLake-UI's `docker-compose-v1.yml` for places that set it.
- The two projects pin different Iceberg versions: Fusion uses `1.7.2` (`iceberg.version` in `pom.xml`), OLake's Java writer uses `1.10.2` (`version.iceberg` in `destination/iceberg/olake-iceberg-java-writer/pom.xml`). 
Always read Iceberg at the version the project pins. The local checkout's working tree may be on a different version, so read the matching tag instead, without changing the checkout: `git -C <iceberg path> show apache-iceberg-1.7.2:<file>` (or `apache-iceberg-1.10.2` for OLake). Do not rely on APIs that do not exist in the pinned version.

Note: ask the user to set the paths of the cloned codebases if not set.

## 4. OLake-UI and Fusion

### Orchestration

**OLake-UI orchestrates compaction with crons.** In OLake-UI's Maintenance pages, users enable
optimization per table and set a cron for each compaction type. Fusion then compacts a table only
when one of its crons fires. A table with optimization enabled but no cron firing is never
compacted.

### Terminology

OLake-UI and the Fusion code use different names for the same things. When the user uses an
OLake-UI term, map it to the Fusion term in the code.

| OLake-UI | Fusion code and API |
| --- | --- |
| Lite | Minor |
| Medium | Major |
| Full | Full |
| Run, run history | Optimizing process (`.../optimizing-processes`) |
| JDBC Catalog | Custom Catalog |

### Table settings and their defaults

OLake-UI's backend writes these as Iceberg table properties (see "APIs" below). When a user enables
optimization on a table that has none of the three cron properties yet, OLake-UI fills in the
defaults.

| OLake-UI setting (request field) | Table property | OLake-UI default |
| --- | --- | --- |
| Enabled for optimization (`enabled_for_optimization`) | `self-optimizing.enabled` | false (from the catalog default below) |
| Lite schedule (`minor_cron`) | `self-optimizing.minor.trigger.cron` | `0 * * * *` (every hour) |
| Medium schedule (`major_cron`) | `self-optimizing.major.trigger.cron` | `0 */8 * * *` (every 8 hours) |
| Full schedule (`full_cron`) | `self-optimizing.full.trigger.cron` | Empty (Full never runs) |
| Target file size, in MB (`target_file_size`) | `self-optimizing.target-size`, in bytes | 512 MB (Fusion's own default is 128 MB) |

The schedule options in OLake-UI are Never (an empty cron), every 30 minutes, every hour, every 8
hours, every 12 hours, every 24 hours (`0 0 * * *`), and a custom cron. OLake-UI gives the option of Bulk Edit as well. Also note that, from OLake-UI if the crons / target file size is configured for an iceberg table automatically the table is enabled for optimization. Also, if the table is enabled for optimization, the cron default values are set.

**OLake-ingested tables.** OLake-UI marks a table as written by OLake (`olake_created: true` in its
table list, shown under the "OLake Ingested" filter; other tables are "Imported Tables") when the
table's properties, stored in its `metadata.json`, contain the key `olake_2pc`. OLake's writer keeps
its two-phase-commit state in that property. Fusion does not set it.

### Catalog settings and their defaults

When OLake-UI creates a catalog in Fusion, it sets these catalog properties unless they are already
given.

| Catalog property | Value | Purpose |
| --- | --- | --- |
| `table.self-optimizing.enabled` | `false` | Tables start with optimization off |
| `table.self-optimizing.quota` | `0.1` | Share of the optimizer each table may use |
| `cache-enabled` | `false` | Catalog caching off |
| `created-at` | Creation date | Shown in OLake-UI |
| `olake_created` | `true`, only when the catalog was imported from an OLake destination | Marks the catalog as OLake's in OLake-UI's catalog list |
| `olake-catalog-type` | The original OLake catalog type (for example `lakekeeper`, `unity`, etc.) | OLake-UI sends Fusion only `glue`, `rest`, `hive` or `custom`; other REST Catalog type cannot be mapped back. This keeps the original so OLake-UI can map it back. |
| `olake-rest-auth-type` | The REST auth type the user picked (for example `Token`), only for REST-style catalogs | Fusion gets the converted Iceberg auth type (for example `oauth2`); this keeps the user's choice for display and edits |

The mapping lives in `server/internal/services/optimization/mapper.go`.

### APIs

OLake-UI's frontend calls `/api/opt/v1/...` on OLake-UI's Go backend, which serves them in one of
two ways:

1. **Handled by OLake-UI itself** (`server/routes/router.go`), which then calls Fusion's upstream
   Amoro APIs:
   - **Catalogs:** `GET /api/opt/v1/catalog/resources/spec`, `POST /api/opt/v1/catalog`, and
     `GET`, `PUT`, `DELETE /api/opt/v1/catalog/{catalog}`. The request is built from OLake-UI's own
     catalog spec (`server/internal/services/optimization/resources/spec.json`, similar to OLake's)
     and `mapper.go`.
   - **Table settings:** `PUT /api/opt/v1/{catalog}/{database}/tables/config`. OLake-UI turns the
     request fields into table properties and runs
     `ALTER TABLE {database}.{table} SET TBLPROPERTIES (...)` through Fusion's terminal API
     (`POST /api/ams/v1/terminal/catalogs/{catalog}/execute`), then polls
     `/api/ams/v1/terminal/{sessionId}/logs` until it finishes (5-minute timeout). See
     `server/internal/services/optimization/terminal.go`.
   - **Table list:** `GET /api/opt/v1/{catalog}/{database}/tables`. For each table, OLake-UI calls
     Fusion's table details (size, properties, health score) and the latest Minor, Major and Full
     optimizing process, and builds one response. See `server/internal/services/optimization/table.go`.
2. **Forwarded to Fusion unchanged.** Any other `/api/opt/v1/<path>` is proxied to Fusion's
   `/api/ams/v1/<path>` (`server/internal/handlers/optimization/piggyBacking.go`), and the response
   is wrapped in `{"success":true,"message":"request forwarded successfully","data":...}`.

Changing any of these Fusion endpoints, the terminal API, or the table-details response changes
what OLake-UI receives; see section 6.

Note: the token authentication logic for Fusion used by OLake-UI can be found here: `server/internal/services/optimization/client.go`

## 7. Repo rules

- Every file you modify that carries the Apache license header must also contain
  `Modified by Datazip Inc. in <year>` within its first 40 lines. The CI check
  `.github/workflows/modification-header-check.yml` fails otherwise. If the line is missing, add it
  at the end of the license comment, in the same style as other modified files.
- Java 17. Before handing over, format each module you changed with
  `./mvnw -o -q spotless:apply -pl <module>` (a few seconds, touches only that module).
  `make spotless-fix` formats the whole repo and can rewrite files you did not touch.

## 8. Testing

### Tests in the diff

Do not add unit or integration tests to the final change unless asked. You may write temporary
tests to check your work; delete them before finishing and report what they showed.

### Pick the optimal check that proves the change

1. **Compile** the touched modules (step 3 of "Run the server from source" below). Always do this.
2. **A temporary unit test** in the touched module, when the logic can be isolated.
3. **Run the server from source** (below) when the behaviour depends on the server: scheduling,
   commit, REST API, config.
4. **Run a local optimizer** when the change is in code that runs in the optimizer.
5. **End-to-end through OLake-UI**, only when the user asks.

Never build a Docker image for levels 1 to 4.

### Run the server from source (no image)

Prerequisites: Docker is running, and the repo has been fully built at least once
(`dist/target/*.tar.gz` exists). `make build` is the full build and takes several minutes.

1. Start only Postgres (5432) and MinIO (9000, console 9001) with `make start-deps`.
2. Create a throwaway database (for example `fusion_dev`) in the `postgres` container, user
   `iceberg`. Never point the server at an existing database: `local-test/` persists Postgres data
   between runs, and an existing database can hold catalogs that point at real cloud storage (for
   example Glue + S3). The server syncs those tables on startup, and its maintenance jobs would act
   on them.
3. Compile `amoro-ams` and every module it depends on, and write its runtime classpath to a file,
   with Maven's `test-compile` and `dependency:build-classpath` in one offline run (about 30–45
   seconds). Sibling modules then resolve to their `target/classes`, so edits in
   `amoro-format-iceberg`, `amoro-common` and so on are picked up without installing them.
4. Start `org.apache.amoro.server.AmoroServiceContainer` with a Java 17 binary, in the background,
   with output in a log file, and save its PID.
   - Classpath: `amoro-ams/target/classes` followed by the contents of the classpath file.
   - JVM flags: the `vmArgs` of the `AmoroServiceContainer` launch configuration in
     `CONTRIBUTING.md` (the `--add-opens` flags are required on Java 17).
   - Environment:
     - `AMORO_HOME=$PWD/dist/src/main/amoro-bin`
     - `AMORO_CONF_DIR=$PWD/local-test`
     - `AMS_SERVER__EXPOSE__HOST=127.0.0.1`
     - `AMS_DATABASE_URL=jdbc:postgresql://localhost:5432/<throwaway database>`
     - `AWS_ACCESS_KEY_ID=admin`, `AWS_SECRET_ACCESS_KEY=password`, `AWS_REGION=us-east-1`: the
       MinIO credentials that Iceberg's S3FileIO reads. Without them, registering a catalog fails.
     - `CONSOLE_LOG_LEVEL=info`

- The server is ready when `curl -sf http://localhost:1630/` succeeds, usually within 10 seconds.
- To drive it, log in with `POST /api/ams/v1/login` (headers `X-Request-Source: Web` and
  `Content-Type: application/json`, body `{"user":"admin","password":"password"}`) and keep the
  cookie jar. Log in again after every restart.
- Create an optimizer group for the local optimizer:
  `POST /api/ams/v1/optimize/resourceGroups` with
  `{"name":"local","container":"localContainer","properties":{"memory":"1024"}}`.
- To register a catalog, reuse the catalog payload from the `fusion-init` service in
  `local-test/docker-compose.yml`, with these changes, because the server runs on the host and not
  on the Docker network:
  - `10.210.30.5:9000` becomes `localhost:9000`;
  - `postgres:5432/iceberg` becomes `localhost:5432/fusion_dev`;
  - `"optimizerGroup":"spark-container"` becomes `"optimizerGroup":"local"`.

  Registration runs a connection test that creates `test_olake.test_olake` in the catalog.
- Verify through API responses, the server log, and table state (metadata in Postgres, files in
  MinIO).
- After an edit: stop the server, redo step 3, start it again.
- To stop the server, `kill <PID>`.

### Code that runs in the optimizer

The local optimizer is a separate process, started by the server through `bin/optimizer.sh` with the
`localContainer` from `local-test/config.yaml`. It loads **jars** from
`dist/src/main/amoro-bin/lib/`, not the compiled classes, so it keeps running old code until those
jars are replaced. This covers the rewrite executors in `amoro-format-iceberg` and the
`amoro-optimizer-common` and `amoro-optimizer-standalone` modules.

After changing optimizer-side code:

1. If `dist/src/main/amoro-bin/lib/` is empty or missing, fill it once with `make sync-libs` (a few
   seconds).
2. Rebuild the changed module's jar with Maven `package`, offline, using `-pl <module path> -am`
   and `-DskipTests` plus the same skip flags as the server compile (about 15 seconds). Use
   `package`, not `install`: `install` needs the rat plugin and sibling jars in `~/.m2`.
3. Copy `<module path>/target/<artifactId>-0.9-SNAPSHOT.jar` into `dist/src/main/amoro-bin/lib/`,
   replacing the jar of the same name.

- Start an optimizer with the server running:
  `POST /api/ams/v1/optimize/optimizerGroups/local/optimizers` with `{"parallelism":1}`.
  `GET` on the same path (with `?page=1&pageSize=10`) should show it as `RUNNING`.
- Its log is `dist/src/main/amoro-bin/logs/optimizer-local-<id>/optimizer.log.err`. The server
  log line `Starting local optimizer using command` gives the `<id>`.
- The optimizer is its own process: stopping the server does not stop it. Kill it by PID
  (`ps -eo pid,args | grep "[S]tandaloneOptimizer "`) before you restart with a new jar.

### Clean up

When done testing:
- Stop the optimizer and the server.
- Revert temporary code changes and tests. Then redo step 3 of "Run the server from source" (and `package` for
  optimizer modules), so `target/` no longer holds the test code.
- If you swapped jars into `lib/`, run `make sync-libs` to restore the built ones.
- Delete your optimizer log directories under `dist/src/main/amoro-bin/logs/`.
- Drop the throwaway database (`docker exec postgres dropdb -U iceberg fusion_dev`), then run
  `make stop-deps` if the dependencies were not already running before you started.
- Check that `git status` shows only the changes you meant to leave.

### End-to-end through OLake-UI (only when asked)

1. In `olake-ui/docker-compose-v1.yml`, make the `olake-ui` service build from local source (It will include the changes, if made, in OLake-UI):

   ```yaml
   olake-ui:
     # image: ${CONTAINER_REGISTRY_BASE:-registry-1.docker.io}/olakego/ui:latest
     # pull_policy: always
     build:
       context: .
       dockerfile: Dockerfile
   ```

2. Choose where Fusion comes from, then start the stack from the `olake-ui` directory.

   **a. Fusion already running outside the stack** (for example from source, as above, on port
   1630). Start the stack without the `fusion` profile, so no Fusion container is created:

   ```shell
   ENABLE_OPTIMIZATION=true docker compose -f docker-compose-v1.yml up -d --build
   ```

   `OPTIMIZATION_BASE_URL` on the `olake-ui` service points at `http://fusion.olake.internal:1630`, a name that only exists when the stack's own Fusion container runs. Point it at the host/IP instead.

   To verify, log in to OLake-UI with `POST http://localhost:8000/login` (body
   `{"username":"admin","password":"password"}`, keep the cookie jar). Then call Fusion throughthecloud
   OLake-UI's server. It forwards `/api/opt/v1/<path>` to Fusion's `/api/ams/v1/<path>` only when it has no
   route of its own for that path. It handles these itself (`server/routes/router.go`), mapping
   them through `server/internal/services/optimization/mapper.go` instead of forwarding them:
   `GET /api/opt/v1/catalog/resources/spec` and `POST`, `GET`, `PUT`, `DELETE` on
   `/api/opt/v1/catalog[/:catalog]`. Forwarded paths work as-is:
   `GET http://localhost:8000/api/opt/v1/versionInfo` should return Fusion's response wrapped in
   `{"success":true,"message":"request forwarded successfully",...}`. The stack starts in about a
   minute when the olake-ui image build is cached. A `signup-init` 409 ("user already exists") is
   harmless.

   Stop the stack with `docker compose -f docker-compose-v1.yml down` (no `-v`, which would delete
   OLake-UI's data volumes).

   **b. Fusion from a Docker image.** Start the stack with the `fusion` profile:

   ```shell
   ENABLE_OPTIMIZATION=true docker compose --profile fusion -f docker-compose-v1.yml up -d --build
   ```

   By default this runs the published `olakego/fusion:latest`, with Fusion's `config.yaml`
   downloaded from OLake-UI's GitHub `master`, so local Fusion changes are not in it. To test local
   changes, build the image first.

   Then set `image: olakego/fusion:local-test` on the `fusion` service in `docker-compose-v1.yml`.
   Use a separate tag, not `latest`: a local `olakego/fusion:latest` would keep shadowing the published image after the test. Before starting, check every image tag the `fusion` profile uses (`fusion`, `spark-copy`,
   `kind-load-image`). A tag that exists neither locally nor on Docker Hub stops the whole stack with `not found`.

   For optimizer-side changes, also build the Spark image locally
   (`docker/optimizer-spark/Dockerfile`; see the Spark job in
   `.github/workflows/docker-images.yml` for the Maven command). Unlike the Fusion image, it must be
   tagged `olakego/fusion-spark:latest`. That tag is hard-coded in `spark-copy`, in
   `kind-load-image`, and in the `config.yaml` that `fusion-db-init` downloads from GitHub
   (`spark.kubernetes.container.image`, with pull policy `IfNotPresent`). That file cannot be
   changed without also changing `fusion-db-init`. `kind-load-image` loads the local image into Kind if it exists, and
   skips it otherwise. Because this local `latest` hides the published Spark image, remove it in
   teardown.

   To verify:
   - `docker inspect olake-fusion --format '{{.Config.Image}}'` shows your tag.
   - `docker logs fusion-init` ends with `Fusion is ready!`.
   - The Spark optimizer pod is `Running`:
     `docker exec fusion-cluster-control-plane kubectl --kubeconfig /etc/kubernetes/admin.conf get pods -n spark`.
   - Your change is visible through Fusion (`localhost:1630`) and through OLake-UI
     (`localhost:8000/api/opt/v1/...`, as in a).

   To tear down:

   ```shell
   docker compose --profile fusion -f docker-compose-v1.yml down   # no -v
   kind delete cluster --name fusion-cluster
   docker rmi olakego/fusion:local-test
   docker rmi olakego/fusion-spark:latest   # only if you built it locally
   ```

   Then revert the compose change and your code change, and rerun the Maven command above, so
   `target/` and the dist tarball no longer contain the test code.

3. Do not commit the changes used just for testing.

