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

## 1. What this repo is

OLake-Fusion is a fork of Apache Amoro (Iceberg compaction and table-maintenance engine written in Java). After fork project got renamed to Fusion. At some places in code it still mentions Amoro.

## 2. Scope

Fusion is a scoped-down fork: it does not need the full Amoro project. Fusion is compaction for
Iceberg tables.

Fusion has exactly **one optimizer group and one optimizer**. There is no multi-optimizer concept:
do not add code, config or tests for several groups or several optimizers. In deployments the group
is `spark-container` (OLake-UI's `OPTIMIZATION_GROUP`); for local debugging it is the `local` group
from section 8.

**In scope:** Iceberg tables in Iceberg-only catalogs, meaning catalogs whose `tableFormatList` is
only `ICEBERG` (for example the `custom` JdbcCatalog in `local-test/`).

**Out of scope unless asked.** Do not read, fix or refactor these. If a change to shared code also
affects them, mention it in one line and move on.

- Mixed-format tables (mixed-iceberg, mixed-hive), Hive, Paimon and Hudi tables, and the modules
  that exist only for them: `amoro-format-mixed/*`, `amoro-format-hudi`, `amoro-format-paimon`.
- The Flink optimizer, `amoro-optimizer/amoro-optimizer-flink`. Fusion does not use it. Never use
  it to run or test compaction, and do not suggest it.
- Amoro's own web UI, `amoro-web` (served by Fusion on port 1630). It is not the product UI;
  OLake-UI is. Ask before changing anything there.

**Modules that usually matter:**

| Module | Runs in | Contains |
| --- | --- | --- |
| `amoro-ams` | Server | Scheduling, commit, table runtime, REST API, keeper |
| `amoro-format-iceberg` | Server and optimizer | Evaluators and planners (server), rewrite executors (optimizer) |
| `amoro-common` | Both | Shared types and config |
| `amoro-optimizer/amoro-optimizer-common`, `-standalone`, `-spark` | Optimizer | Shared optimizer code, the local optimizer, the production optimizer |

**Compaction runs on the Spark optimizer** (`amoro-optimizer-spark`, image `olakego/fusion-spark`)
on Kubernetes. The local standalone optimizer is only for local debugging.

## 3. Deployment and orchestration

Fusion is deployed together with OLake-UI, in one of two modes:

- **Docker:** OLake-UI's `docker-compose-v1.yml`. The Fusion services are under the `fusion`
  profile and need `ENABLE_OPTIMIZATION=true`. Fusion runs as a container, and the Spark optimizer
  runs on a local Kind cluster that the stack creates.
- **Kubernetes:** OLake-Helm's chart (`helm/olake`). Fusion's resources are in
  `templates/fusion/` and are switched on with `fusion.enabled` (default `false`).

`local-test/` in this repo is only for manual testing (section 8), not a product deployment.

**OLake-UI orchestrates compaction with crons.** In OLake-UI's Maintenance pages, users enable
optimization per table and set a cron for each compaction type. Fusion then compacts a table only
when one of its crons fires: `TableRuntimeRefreshExecutor` marks the table pending for that
optimizing type. A table with optimization enabled but no cron firing is never compacted. Keep this
in mind when testing: nothing happens until a cron fires.

## 4. Terminology

OLake-UI and the Fusion code use different names for the same things:

| OLake-UI | Fusion code and API |
| --- | --- |
| Lite | Minor (`MINOR`) |
| Medium | Major (`MAJOR`) |
| Full | Full (`FULL`) |
| Run, run history | Optimizing process (`.../optimizing-processes`) |
| Lite schedule (request field `minor_cron`) | `self-optimizing.minor.trigger.cron` |
| Medium schedule (request field `major_cron`) | `self-optimizing.major.trigger.cron` |
| Full schedule (request field `full_cron`) | `self-optimizing.full.trigger.cron` |
| Enabled for optimization (`enabled_for_optimization`) | `self-optimizing.enabled` |
| Target file size, in MB (`target_file_size`) | `self-optimizing.target-size`, in bytes, default from OLake-UI: 512 MB |
| Catalog, created from an OLake Iceberg destination | Catalog. OLake catalog type `jdbc` becomes Fusion type `custom`; `glue`, `rest` and `hive` keep their names. |

When the user uses an OLake-UI term, map it to the Fusion term in the code.

## 5. Related repos

| Repo | Local path | Role |
| --- | --- | --- |
| OLake-UI | `<path>` | Product UI and BFF for Fusion. Its Go server proxies Fusion's REST API (`/api/ams/v1/...`) through `OPTIMIZATION_BASE_URL`. Its `docker-compose-v1.yml` is also the Docker deployment of Fusion. |
| OLake-Helm | `<path>` | Helm deployment of Fusion |
| OLake | `<path>` | Ingestion; writes the Iceberg tables Fusion maintains |
| Iceberg | `<path>` | Apache Iceberg Java source |

- If you change a Fusion REST endpoint's path, request or response, grep OLake-UI for its callers
  and report what would break.
- If you add or rename a config key, port or env var, check OLake-Helm and OLake-UI's
  `docker-compose-v1.yml` for places that set it.
- The two projects pin different Iceberg versions: Fusion uses `1.7.2` (`iceberg.version` in
  `pom.xml`), OLake's Java writer uses `1.10.2` (`version.iceberg` in
  `destination/iceberg/olake-iceberg-java-writer/pom.xml`). Always read Iceberg at the version the
  project pins. The local checkout's working tree may be on a different version, so read the
  matching tag instead, without changing the checkout:
  `git -C <iceberg path> show apache-iceberg-1.7.2:<file>` (or `apache-iceberg-1.10.2` for OLake).
  Do not rely on APIs that do not exist in the pinned version.

Note: OLake-UI uses a custom spec (similar to OLake) and mapping logic to create catalog in Fusion.

## 6. Repo rules

- Every file you modify that carries the Apache license header must also contain
  `Modified by Datazip Inc. in <year>` within its first 40 lines. The CI check
  `.github/workflows/modification-header-check.yml` fails otherwise. If the line is missing, add it
  at the end of the license comment, in the same style as other modified files.
- Java 17. Before handing over, format each module you changed with
  `./mvnw -o -q spotless:apply -pl <module>` (a few seconds, touches only that module).
  `make spotless-fix` formats the whole repo and can rewrite files you did not touch.
- PRs target `staging`. Only `staging` merges into `master`.
- Do not commit, push, rebase or squash. Leave changes in the working tree.

## 7. Working style

- **Ask before guessing.** If a request has more than one reading, or touches behaviour you are
  unsure of, say what is unclear and ask before writing code. State the assumptions you do make.
- **Smallest change that solves the problem.** No features, options or abstractions that were not
  asked for, and no error handling for cases that cannot happen.
- **Surgical diffs.** Do not reformat, rename or refactor code you did not need to touch. Match the
  surrounding style. Mention unrelated dead code or bugs instead of fixing them. Remove imports or
  helpers that your own change made unused.
- **Define "done" before starting.** For any non-trivial task, write down how you will verify it
  (see section 8), then verify it before reporting success. Say plainly what was and was not
  tested.

## 8. Testing

### Tests in the diff

Do not add unit or integration tests to the final change unless asked. You may write temporary
tests to check your work; delete them before finishing and report what they showed.

### Pick the cheapest check that proves the change

1. **Compile** the touched modules (command below). Always do this.
2. **A temporary unit test** in the touched module, when the logic can be isolated (about 15
   seconds):

   ```shell
   ./mvnw -o test -pl <module> -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false \
     -Pskip-dashboard-build -Dspotless.skip=true -Dcheckstyle.skip=true -Drat.skip=true
   ```

   Check the output for `Tests run: N` with N > 0. A misspelled class name still ends in
   `BUILD SUCCESS`, with no tests run.
3. **Run the server from source** (below) when the behaviour depends on the server: scheduling,
   commit, REST API, config.
4. **Run a local optimizer** when the change is in code that runs in the optimizer.
5. **End-to-end through OLake-UI**, only when the user asks.

Never build a Docker image for levels 1 to 4.

All Maven commands in this section run offline (`-o`). If one fails with "Cannot access ... in
offline mode" for a plugin or third-party artifact, run it once without `-o` to download it, then
go back to `-o`. Do not fix such an error with `make build`, `install` or a Docker build. Errors
about sibling `org.apache.amoro` artifacts mean `-am` is missing.

### Run the server from source (no image)

Prerequisites: Docker is running, and the repo has been fully built at least once
(`dist/target/*.tar.gz` exists). `make build` is the full build and takes several minutes.

```shell
make start-deps   # Postgres (5432) and MinIO (9000, console 9001) only

# Use a throwaway database. local-test/ may persist Postgres data between runs, and an existing
# database can hold catalogs that point at real cloud storage (for example Glue + S3). The
# server syncs those tables on startup, and its maintenance jobs (snapshot expiry, orphan-file
# cleaning) would act on them.
docker exec postgres createdb -U iceberg fusion_dev

# Compile the server and every module it depends on, and write its runtime classpath.
# About 30-45 seconds. Sibling modules resolve to their target/classes, so edits in
# amoro-format-iceberg, amoro-common etc. are picked up without installing them.
# - Keep -am: without it, sibling modules are looked up in ~/.m2 and fail offline.
# - Keep test-compile (not compile): it also resolves sibling test jars from target/test-classes.
# - Keep the output path absolute: a relative one is written once per module, under each
#   module's own directory.
./mvnw -o -q -pl amoro-ams -am test-compile dependency:build-classpath \
  -Dmdep.outputFile="$PWD/amoro-ams/target/ams-cp.txt" \
  -Pskip-dashboard-build -Dspotless.skip=true -Dcheckstyle.skip=true -Drat.skip=true

# Start the server in the background, with output in a log file, and save its PID ($!).
# Use a Java 17 binary: `java` if `java -version` says 17, on macOS
# "$(/usr/libexec/java_home -v 17)/bin/java".
# The AWS_* variables are the MinIO credentials that Iceberg's S3FileIO reads.
AMORO_HOME="$PWD/dist/src/main/amoro-bin" \
AMORO_CONF_DIR="$PWD/local-test" \
AMS_SERVER__EXPOSE__HOST=127.0.0.1 \
AMS_DATABASE_URL=jdbc:postgresql://localhost:5432/fusion_dev \
AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password AWS_REGION=us-east-1 \
CONSOLE_LOG_LEVEL=info \
java \
  -Dfile.encoding=UTF-8 -Darrow.memory.allocator=unsafe -XX:+UseZGC \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED --add-opens=java.base/sun.security.action=ALL-UNNAMED \
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
  -cp "amoro-ams/target/classes:$(cat amoro-ams/target/ams-cp.txt)" \
  org.apache.amoro.server.AmoroServiceContainer
```

- The server is ready when `curl -sf http://localhost:1630/` succeeds, usually within 10 seconds.
  A logged `relation "ams_schema_migration" does not exist` on a fresh database is harmless.
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
- After an edit: stop the server, rerun the compile command, start it again.
- To stop the server, `kill <PID>`. Shutdown sometimes hangs; if the process is still alive after
  30 seconds, `kill -9 <PID>`. Do not use `pkill -f` or `pgrep -f` with a pattern that also
  appears in your own command line (for example a class name inside a file path you pass): it
  matches and kills your own shell, which drops an SSH session.

### Code that runs in the optimizer

The local optimizer is a separate process, started by the server through `bin/optimizer.sh` with the
`localContainer` from `local-test/config.yaml`. It loads **jars** from
`dist/src/main/amoro-bin/lib/`, not the compiled classes, so it keeps running old code until those
jars are replaced. This covers the rewrite executors in `amoro-format-iceberg` and the
`amoro-optimizer-common` and `amoro-optimizer-standalone` modules.

After changing optimizer-side code:

```shell
# Once, if dist/src/main/amoro-bin/lib/ is empty or missing (a few seconds):
make sync-libs

# Rebuild the changed module's jar (about 15 seconds) and swap it in.
# Use package, not install: install needs the rat plugin and sibling jars in ~/.m2.
./mvnw -o -q package -pl <module path> -am -DskipTests \
  -Pskip-dashboard-build -Dspotless.skip=true -Dcheckstyle.skip=true -Drat.skip=true
cp <module path>/target/<artifactId>-0.9-SNAPSHOT.jar dist/src/main/amoro-bin/lib/
```

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
- Revert temporary code changes and tests. Then rerun the compile command (and `package` for
  optimizer modules), so `target/` no longer holds the test code.
- If you swapped jars into `lib/`, run `make sync-libs` to restore the built ones.
- Delete your optimizer log directories under `dist/src/main/amoro-bin/logs/`.
- Drop the throwaway database (`docker exec postgres dropdb -U iceberg fusion_dev`), then run
  `make stop-deps` if the dependencies were not already running before you started.
- Check that `git status` shows only the changes you meant to leave.

### End-to-end through OLake-UI (only when asked)

1. In `olake-ui/docker-compose-v1.yml`, make the `olake-ui` service build from local source:

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

   `OPTIMIZATION_BASE_URL` on the `olake-ui` service points at `http://fusion.olake.internal:1630`,
   a name that only exists when the stack's own Fusion container runs. Point it at the host
   instead:
   - macOS (Docker Desktop): `http://host.docker.internal:1630`.
   - Linux: `host.docker.internal` does not resolve by default. Use the host's own IP
     (`ip -4 addr`, for example `http://172.31.32.4:1630`), or add
     `extra_hosts: ["host.docker.internal:host-gateway"]` to the `olake-ui` service.

   Fusion from source binds to `0.0.0.0`, so check it first from the host with
   `curl -s -o /dev/null -w "%{http_code}" http://<host IP>:1630/` (expect `200`).

   To verify, log in to OLake-UI with `POST http://localhost:8000/login` (body
   `{"username":"admin","password":"password"}`, keep the cookie jar). Then call Fusion through the
   BFF: OLake-UI forwards `/api/opt/v1/<path>` to Fusion's `/api/ams/v1/<path>`, so
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
   changes, build the image first (about 3 minutes for each command):

   ```shell
   # in olake-fusion. `clean` wipes every target/ directory, including the dist tarball.
   ./mvnw clean package -pl dist -am -DskipTests -Psupport-all-formats -Pno-extended-disk-storage -Pno-plugin-bin
   docker build -f docker/amoro/Dockerfile -t olakego/fusion:local-test .
   ```

   Then set `image: olakego/fusion:local-test` on the `fusion` service in `docker-compose-v1.yml`.
   Use a separate tag, not `latest`: a local `olakego/fusion:latest` would keep shadowing the
   published image after the test.

   Before starting, check every image tag the `fusion` profile uses (`fusion`, `spark-copy`,
   `kind-load-image`). A tag that exists neither locally nor on Docker Hub stops the whole stack
   with `not found`. For optimizer-side changes, also build the Spark image locally
   (`docker/optimizer-spark/Dockerfile`; see the Spark job in
   `.github/workflows/docker-images.yml` for the Maven command). `kind-load-image` loads that image
   into Kind if it exists locally, and skips it otherwise.

   Needs about 10 GB of free disk: the Fusion image (about 2.2 GB) and its build cache, the Spark
   image (about 1.3 GB compressed), and the Kind node image. The full stack (Kind cluster with
   three nodes, Fusion, OLake-UI) takes about 5 minutes to start.

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
   ```

   Then revert the compose change and your code change, and rerun the Maven command above, so
   `target/` and the dist tarball no longer contain the test code.

3. Do not commit the `docker-compose-v1.yml` changes.

### `local-test/`

`local-test/` holds the deployment setup for manual testing.
