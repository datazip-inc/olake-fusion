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

# Contributing to OLake-Fusion

OLake-Fusion is the Iceberg compaction and table-maintenance engine behind OLake. The general
process is in the OLake docs: [Contribute to OLake](https://olake.io/docs/fusion/community/contributing/),
[How to raise a PR](https://olake.io/docs/fusion/community/issues-and-prs/),
[Bounty program](https://olake.io/docs/fusion/community/issues-and-prs#goodies). This page covers
what is specific to this repository.

Questions: [Slack](https://olake.io/slack/) or
[GitHub Issues](https://github.com/datazip-inc/olake-fusion/issues/new). Starter tasks:
[good first issues](https://github.com/datazip-inc/olake-fusion/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).

## Issues

Open an issue for every change; search for an existing one first. Types: Feature, Improvement, Bug,
Subtask. Comment `take` to assign it to yourself.

## Pull requests

* Target the `staging` branch and link the issue (`fixes #<number>` in the description).
* Title in Conventional Commits form, `type(scope): description`, for example
  `fix(ams): recover tables stranded in planning`. Types: `feat`, `fix`, `docs`, `style`,
  `refactor`, `test`, `chore`, `build`, `ci`, `perf`, `revert`. CI rejects other titles.
* Describe what changed and how you tested it, with the results.
* Fill in the `Documentation` section of the PR template: a documentation link, or `N/A` for bug
  fixes, refactors and test-only changes. New features need user documentation.
* **Attach a demo video** (below).
* For a large change, agree on the design in the issue or on Slack before writing code.

### Demo video (required)

A two- or three-minute recording of your change running on your own build: `make debug` +
`make debug-server` (or the IDE), or the full stack via `make start-fusion-docker`. It shows that
the change was built and run, not only generated. Show:

* bug fixes: the failure, then the same case passing;
* features: the feature used end to end;
* the evidence: log output, the web UI or the resulting table state.

A change with nothing to show (a pure refactor) needs a recording of the relevant tests passing;
"it builds" is not enough. The video is required for every PR.

## Code review

Reviewers check that the PR meets the rules above and that the video shows the described
behaviour, then look for: tests for the change, Javadoc on public APIs, comments on complex logic,
user documentation for features, and duplicated code. Approve once every comment is resolved.

## Build and test

Requires JDK 17 and Docker. The `Makefile` finds JDK 17 itself; `make help` lists all targets.

```shell
$ make build                                                  # clean build, install to ~/.m2
$ ./mvnw test -pl <module> -am                                # one module's tests
$ ./mvnw test -pl <module> -am -Dtest=ConfigurationsTest      # one test class
```

Set `JAVA_HOME` to JDK 17 before calling `./mvnw` directly.

| Target | Does |
| --- | --- |
| `make debug` | Start Postgres and MinIO, create the `fusion_dev` database, compile |
| `make debug-server` | Run the server from compiled classes (debugger port 5005) |
| `make debug-optimizer` | Run an optimizer from compiled classes (debugger port 5006) |
| `make debug-catalog` | Register catalog `local_iceberg` (JDBC catalog, data in MinIO) |
| `make debug-data ARGS="..."` | Create a table with data and delete files (`ARGS=--help`) |
| `make debug-stop` | Stop the server and optimizer started from this checkout |
| `make debug-reset` / `make debug-clean` | Recreate `fusion_dev` / stop Postgres and MinIO |
| `make vscode` | Install the VS Code debug configurations |
| `make start-fusion-docker` / `make clean-fusion-docker` | Full stack on a Kind cluster |
| `make sync-frontend` | Copy built web assets into the build output (fixes a blank UI) |
| `make spotless-fix` | Fix formatting |

Web UI: <http://localhost:1630>, MinIO console: <http://localhost:9001>, both `admin` / `password`.

## Debug mode

The server and the optimizer run from compiled classes; nothing is copied into `dist/`. After a code
change, recompile and restart. The `make debug-*` targets build into `target-debug/`, so an IDE that
compiles into `target/` cannot break them.

```shell
$ make debug             # once per session
$ make debug-server      # terminal 1
$ make debug-catalog     # once per database, with the server up
$ make debug-optimizer   # terminal 2; registers into the spark-container group
$ make debug-data ARGS='--files 20 --pos 20 --update --minor-cron "* * * * *"'
```

The last command creates `demo.events` with 20 small data files and 20 position delete files that
update rows, compacted by a Minor run every minute, and prints the row count a query should
return. `--eq <n>` writes equality deletes, `--partitions <n>` partitions the table. To compact an
existing table instead, set `self-optimizing.enabled` and a cron such as
`self-optimizing.minor.trigger.cron` on it; new tables are discovered within three minutes.

| Variable | Default | Effect |
| --- | --- | --- |
| `NO_COMPILE=1` | unset | Skip the compile step |
| `DEBUG_SUSPEND=y` | `n` | JVM waits for the debugger before starting |
| `SERVER_DEBUG_PORT`, `OPTIMIZER_DEBUG_PORT` | `5005`, `5006` | Debugger ports |
| `DEBUG_DB` | `fusion_dev` | Server database |
| `DEBUG_PARALLELISM`, `OPTIMIZER_MEMORY_MB` | `1`, `2048` | Optimizer threads and heap |

An optimizer created from the web UI on `localContainer` also runs the compiled classes
(`use-server-classpath` in `local-test/config.yaml`). Fusion runs one optimizer: use that or
`make debug-optimizer`, not both.

### VS Code

Install the [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack),
then:

```shell
$ make vscode
```

It installs `dev/vscode/launch.json` and `tasks.json` into `.vscode/` (old files kept as `*.bak`) and
writes `settings.json` with your JDK 17 path if you have none. Run
`Java: Clean Java Language Server Workspace` and reload the window once afterwards. To change the
configurations for everyone, edit `dev/vscode/`.

| Configuration | Does |
| --- | --- |
| **Fusion: server + optimizer** | Starts Postgres and MinIO, launches both; stopping it stops both |
| **Fusion: server** / **Fusion: optimizer** | Launches one of them |
| **Fusion: attach to server (5005)** | Attaches to `make debug-server` |
| **Fusion: attach to optimizer (5006)** | Attaches to `make debug-optimizer`, or to an optimizer the server started |

Tasks (`Terminal → Run Task`): **fusion: register catalog**, **fusion: create test data**,
**fusion: start Postgres and MinIO**, **fusion: stop debug JVMs**.

To stop on a line: start or attach a configuration, click left of a line number (red dot), and make
the code run, for example by starting an optimizer to hit `DefaultOptimizingService.authenticate`.
The **Run and Debug** sidebar then shows **CALL STACK**, **VARIABLES**, **WATCH** and
**BREAKPOINTS**. F5 continues, F10 steps over, F11 steps into, Shift+F11 steps out. A grey, hollow
breakpoint means the Java extension's project is stale: clean its workspace and reload.

### IntelliJ IDEA

1. Run `make debug`, open the repository root, set the project SDK to JDK 17 and reload the Maven
   projects.
2. Server: an **Application** configuration, main class
   `io.olake.fusion.server.FusionServiceContainer`, module `fusion-ams`, working directory the
   repository root, VM options and environment from **Fusion: server** in `dev/vscode/launch.json`.
3. Optimizer: an **Application** configuration, main class
   `io.olake.fusion.optimizer.standalone.StandaloneOptimizer`, module
   `fusion-optimizer-standalone`, arguments `-a thrift://127.0.0.1:1261 -g spark-container -p 1`,
   VM options and environment from **Fusion: optimizer**.
4. To attach to `make debug-server` / `make debug-optimizer` instead: **Remote JVM Debug** on
   `localhost:5005` / `localhost:5006`.

### Troubleshooting

* **`NoClassDefFoundError: Configurations`** or impossible compile errors in IDE launches: the IDE
  left broken classes in `target/` after a `pom.xml` change. Clean the Java language server
  workspace (VS Code) or reload the Maven projects (IntelliJ), then run
  `./mvnw clean -pl fusion-ams -am -Pskip-dashboard-build`.
* **Blank web UI:** `make sync-frontend`.
* **Optimizer does not register:** start the server first; it creates the `spark-container` group
  at startup.

## Code rules

* **Formatting:** Spotless with google-java-format 1.7 runs in the build. Fix with
  `make spotless-fix`, or format one module with `./mvnw -o -q spotless:apply -pl <module>`. In
  IntelliJ, install the
  [google-java-format plugin 1.7.0.6](https://plugins.jetbrains.com/plugin/8527-google-java-format/versions/stable/115957)
  (newer versions format differently) and enable it with "Default Google Java style".
* **Configuration docs:** after changing a `ConfigOption`, regenerate them with
  `UPDATE=1 ./mvnw test -pl fusion-ams -am -Dtest=ConfigurationsTest`.
* **License header:** every source and configuration file starts with the Apache License 2.0
  header (copy it from any Java file). A file you modify that carries it must also contain
  `Modified by Datazip Inc. in <year>` within its first 40 lines, at the end of the license
  comment; the `modification-header-check` CI job fails otherwise.
