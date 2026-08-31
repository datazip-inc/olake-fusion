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

We truly value and appreciate all contributions — every effort makes a difference, and proper credit
will always be given. OLake-Fusion is the Iceberg compaction and table-maintenance engine behind
OLake, and it is developed entirely in the open.

To keep things consistent across the OLake projects, the general contribution process lives in the
OLake documentation:

- [Contribute To OLake](https://olake.io/docs/fusion/community/contributing/)
- [How To Raise A PR](https://olake.io/docs/fusion/community/issues-and-prs/)
- [Fusion Quickstart](https://olake.io/docs/fusion/getting-started/quickstart/)
- [Contributor rewards: Bounty Program](https://olake.io/docs/fusion/community/issues-and-prs#goodies)

The rest of this document covers what is specific to this repository: review expectations, how to
build and test locally, how to import the project into IntelliJ IDEA or VS Code, how to run Fusion in
debug mode, and the formatting rules the build enforces.

Related repositories:

- Core project: [datazip-inc/olake](https://github.com/datazip-inc/olake)
- UI project: [datazip-inc/olake-ui](https://github.com/datazip-inc/olake-ui)
- Docs and website: [datazip-inc/olake-docs](https://github.com/datazip-inc/olake-docs/)

If you would like to discuss a change before you start writing code, come and say hello:

- [Slack](https://olake.io/slack/)
- [GitHub Issues](https://github.com/datazip-inc/olake-fusion/issues/new)
- [Good First Issues](https://github.com/datazip-inc/olake-fusion/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)

## Issues

Regardless of the type of contribution you plan to make, it is recommended that you create an issue
to track it.

* Before creating an issue, please search within the issues to see if a similar one has already been
  reported.
* Choose the appropriate type:
    * Feature: A new feature to be added.
    * Improvement: Enhancement of an existing feature, including code quality, performance, user
      experience, etc.
    * Bug: A problem that prevents the project from functioning as intended.
    * Subtask: A subtask of a Feature/Improvement that can be broken down into smaller steps.

You can assign the issue to yourself by leaving a comment with content `take`.

## Pull requests

Pull requests are the preferred mechanism for contributing to OLake-Fusion.

* Generally, create a PR only to the staging branch.
* PR should be linked to the corresponding issue.
    * The PR title must follow Conventional Commits: `type(scope): description`, for example
      `fix(ams): recover tables stranded in planning`. Allowed types are `feat`, `fix`, `docs`,
      `style`, `refactor`, `test`, `chore`, `build`, `ci`, `perf` and `revert`. This is enforced by
      the `Master Branch Protection` workflow, and a PR with a non-conforming title will fail CI.
    * Add fix/resolve #{issue_number} in the description to link the PR to the issue.
    * The linked issue should clearly explain the background, objectives, and implementation methods
      of the PR.
* The change log in the PR should clearly describe the changes made in modules, classes, methods,
  etc.
* The PR should include corresponding testing methods, and the test results should be visible.
* **The PR must include a demo video.** See the section below.
* Fill in the `Documentation` section of the PR template. The `Documentation Check` workflow
  requires either a documentation link (README, olake.io/docs, or the olake-docs repository) or an
  explicit `N/A` tick for bug fixes, refactors, and test-only changes.
* If the PR involves new features, the user document should include instructions for its usage.

### Demo video (required)

Every pull request must include a short screen recording that shows your change actually running.

This is a hard requirement, and it exists for one reason: to confirm that a human built the change
and exercised it on a real environment. AI assistants are welcome as tools, but a pull request whose
changes were generated and never run will be closed. The video is the proof that the code works, not
just that it compiles.

The recording should show:

* Fusion running from your own build — started with `make setup-debug-mode` and your IDE run
  configuration, or with the full stack via `make start-fusion-docker`.
* The behaviour before and after your change, wherever the difference is observable.
* For bug fixes: the failing case reproducing first, then the same case passing with your change
  applied.
* For new features: the feature being used end to end.
* The evidence that proves the effect — relevant log output, the web UI, or the resulting table
  state.

Two or three minutes is usually enough. Attach the recording directly to the pull request
description, or link an unlisted upload.

If a change genuinely cannot be demonstrated visually — a pure refactor, for example — say so
explicitly in the description and attach a terminal recording of the relevant tests passing instead.
"It builds" is not sufficient on its own.

## Code review

Code review is a crucial aspect of contributing to a project, and all contributors are encouraged to
actively review and provide feedback on each other's PRs.

* Check whether the PR meet the requirements specified in the previous section on Pull Requests.
* Confirm that the demo video is present and that it actually shows the described behaviour. If it is
  missing, ask for it before reviewing the code in depth.
* Review each file changed by the PR, and consider the following aspects:
    * Is the java doc complete?
    * Is there new unit or integration test coverage for the code changes?
    * Does the user document explain how to use new features?
    * Are there comments to aid in understanding complex logic?
    * Have any duplicate classes or methods been introduced?
* Track feedback on suggestions and their resolution.
* If a suggestion is resolved, please close it.
* If all suggestions are resolved or there are no suggestions, approve it.

## Design document

Write down your implementation plan and discuss it with other developers in the community before you
start coding officially. If it is just a small change, describe the implementation steps clearly in
the Issue. If it is a relatively large work, it is recommended to write a design document for this
feature. Here is
a [design document template](https://docs.google.com/document/d/1LeTyrlzQJfSs2DkRBsucK_vV5gtHRYLb1KSrpu0hp3g/edit?usp=sharing)
for reference.

## Building and testing locally

Java 17 and Docker are required. The `Makefile` wraps the Maven wrapper with an auto-detected JDK 17,
so prefer the make targets over calling `./mvnw` directly:

```shell
$ git clone https://github.com/datazip-inc/olake-fusion.git
$ cd olake-fusion
$ make help     # list every available target
$ make build    # clean build + install to ~/.m2 + sync optimizer libs
```

Everything else is a make target too:

| Target | What it does |
| --- | --- |
| `make setup-debug-mode` | Start local dependencies and build — one-shot setup for IDE debugging |
| `make clean-debug-mode` | Stop local dependencies and clean the extracted runtime |
| `make start-deps` / `make stop-deps` | Postgres and MinIO only |
| `make start-fusion-docker` / `make clean-fusion-docker` | Full stack on a Kind cluster |
| `make sync-libs` | Re-extract the dist tar and sync optimizer libs, without rebuilding |
| `make sync-frontend` | Sync built frontend assets, fixes a blank web UI |
| `make spotless-fix` | Auto-fix formatting violations |

Once running, the web UI is at <http://localhost:1630> (`admin` / `password`) and the MinIO console
at <http://localhost:9001> (`admin` / `password`).

Tests run through the Maven wrapper:

```shell
$ ./mvnw test                                                # whole project
$ ./mvnw test -pl <module> -am                               # one module and its dependencies
$ ./mvnw test -pl <module> -am -Dtest=ConfigurationsTest     # a single test class
```

Point `JAVA_HOME` at a JDK 17 installation before invoking `./mvnw` directly. `make build` handles
this for you.

## Setting up your IDE

Fusion is a multi-module Maven project. Whichever IDE you use, the setup is the same in shape: point
it at a JDK 17, let it import the Maven project, then create a run configuration for the server and
an attach configuration for the optimizer.

Locate your JDK 17 installation first — you will need the path in both IDEs:

```shell
# macOS
$ /usr/libexec/java_home -v 17

# Linux (Debian/Ubuntu)
$ update-java-alternatives --list
# Linux (fallback)
$ ls /usr/lib/jvm
```

### IntelliJ IDEA

This guide is based on IntelliJ IDEA 2024. Some details might differ in other versions.

1. Install the **Scala** plugin from `Settings` → `Plugins` → `Marketplace`, and restart if prompted.
   It is only needed if you work on the Scala modules.
2. Build once from the terminal so dependencies resolve, using `make build`.
3. Open the repository root with `File` → `Open`.
4. Set the project SDK to Java 17 under `File` → `Project Structure...` → `Project` → `SDK`.
5. In the `Maven` tab, expand `Profiles` and make sure the selected Java version matches, then click
   `Reload All Maven Projects`.
6. Create a run configuration for the server:
    * Type: **Application**
    * Main class: `org.apache.amoro.server.AmoroServiceContainer`
    * Module classpath: `amoro-ams`
    * Working directory: the repository root
    * VM options and environment variables: use the same values as the `launch.json` shown in the
      VS Code section below.
7. Create a **Remote JVM Debug** configuration on `localhost:5006` to attach to the local optimizer.

### VS Code

The `.vscode/` directory is intentionally excluded from version control (see `.gitignore`) because
its contents are machine-specific — JDK paths differ between developers and operating systems.
Both `.vscode/settings.json` and `.vscode/launch.json` therefore have to be created locally by each
contributor, and neither should be committed.

Install the
[Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack),
and [Scala (Metals)](https://marketplace.visualstudio.com/items?itemName=scalameta.metals) if you
work on the Scala modules.

#### Configure the Java path in `.vscode/settings.json`

Java language support will not resolve the project until VS Code is told which JDK 17 to use. Create
`.vscode/settings.json`, replacing both occurrences of the path below with your own JDK 17 path:

```json
{
  "java.compile.nullAnalysis.mode": "automatic",
  "java.jdt.ls.java.home": "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home",
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-17",
      "path": "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home",
      "default": true
    }
  ],
  "java.configuration.detectJdksAtStartUp": true,
  "java.configuration.updateBuildConfiguration": "automatic",
  "java.import.maven.arguments": "-Pskip-dashboard-build -DskipTests -Dspotless.skip=true -Dcheckstyle.skip=true",
  "java.import.exclusions": [
    "**/node_modules/**",
    "**/.git/**"
  ],
  "makefile.configureOnOpen": false
}
```

`java.jdt.ls.java.home` is the JDK the language server itself runs on, while
`java.configuration.runtimes` is the JDK used to compile and run the project. Both must point at
Java 17. The `java.import.maven.arguments` entry keeps the initial import fast by skipping the
dashboard build, the tests, and the formatting checks.

After saving the file, run `Java: Clean Java Language Server Workspace` from the Command Palette and
reload the window so the new JDK is picked up.

#### Create `.vscode/launch.json`

Debug configurations are not generated automatically, so create `.vscode/launch.json` with the
following content:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "AmoroServiceContainer",
      "request": "launch",
      "mainClass": "org.apache.amoro.server.AmoroServiceContainer",
      "projectName": "amoro-ams",
      "cwd": "${workspaceFolder}",
      "console": "integratedTerminal",
      "vmArgs": "-Dfile.encoding=UTF-8 -Darrow.memory.allocator=unsafe -XX:+UseZGC -XX:CompileCommand=exclude,io/netty/buffer/PoolChunkList.allocate -XX:CompileCommand=exclude,io/netty/buffer/PoolArena.allocate --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.nio.cs=ALL-UNNAMED --add-opens=java.base/sun.security.action=ALL-UNNAMED --add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
      "env": {
        "AMORO_HOME": "${workspaceFolder}/dist/src/main/amoro-bin",
        "AMORO_CONF_DIR": "${workspaceFolder}/local-test",
        "CONSOLE_LOG_LEVEL": "info",
        "OPTIMIZER_JAVA_OPTS": "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006",
        "AMS_SERVER__EXPOSE__HOST": "127.0.0.1",
        "AMS_DATABASE_URL": "jdbc:postgresql://localhost:5432/iceberg"
      }
    },
    {
      "type": "java",
      "name": "OptimizerStandalone",
      "request": "attach",
      "hostName": "localhost",
      "port": 5006,
      "projectName": "amoro-optimizer-standalone"
    }
  ]
}
```

The two configurations serve different purposes:

* **AmoroServiceContainer** launches the Fusion server itself, with the `--add-opens` flags that
  Iceberg and Arrow require on Java 17. `AMORO_CONF_DIR` points at `local-test/` so the server picks
  up the local `config.yaml`, and `AMS_DATABASE_URL` points at the Postgres container started by
  `make start-deps`.
* **OptimizerStandalone** attaches to an already-running local optimizer. The server starts that
  optimizer with the JDWP agent configured through `OPTIMIZER_JAVA_OPTS`, listening on port `5006`,
  so this configuration attaches to it rather than launching it.

If you change the JDWP port in `OPTIMIZER_JAVA_OPTS`, change the `port` of the attach configuration
to match.

## Running Fusion in debug mode

1. Set up your IDE as described above.
2. Run the one-shot setup:

   ```shell
   $ make setup-debug-mode
   ```

   This starts the local dependencies (Postgres and MinIO), runs a full build, extracts the dist
   tarball, and syncs the optimizer runtime jars into place.
3. Start the server with the **AmoroServiceContainer** run configuration.
4. Open the web UI at <http://localhost:1630> (`admin` / `password`) and add an optimizer group and a
   local optimizer.
5. Once the optimizer is running, attach to it with the **OptimizerStandalone** configuration, or the
   equivalent remote debug configuration in IntelliJ IDEA.
6. Set breakpoints and confirm they are hit.

Tear everything down with:

```shell
$ make clean-debug-mode
```

### Troubleshooting

* **Unresolved imports or missing project sources.** In VS Code, run
  `Java: Clean Java Language Server Workspace` from the Command Palette and reload the window. In
  IntelliJ IDEA, reload the Maven projects. If the problem persists, confirm your JDK 17 path is
  correct and that `make build` succeeds from the terminal.
* **Blank web UI.** The frontend assets are missing from `target/`. Run `make sync-frontend`.
* **The optimizer debugger will not attach.** Make sure the server is running and that an optimizer
  has actually been created through the UI. The JDWP listener only exists once the server has spawned
  the optimizer process.
* **Still stuck.** Ask on [Slack](https://olake.io/slack/).

## Code suggestions

### Code formatting

OLake-Fusion uses [Spotless](https://github.com/diffplug/spotless/tree/main/plugin-maven) together with
[google-java-format](https://github.com/google/google-java-format) to format the Java code. For
Scala, it uses Spotless with [scalafmt](https://scalameta.org/scalafmt/).

The simplest way to fix formatting violations is:

```shell
$ make spotless-fix
```

Or you can configure your IDE to format automatically. In IntelliJ IDEA you will need to install
the [google-java-format](https://github.com/google/google-java-format) plugin. However, a specific
version of this plugin is required.
Download [google-java-format v1.7.0.6](https://plugins.jetbrains.com/plugin/8527-google-java-format/versions/stable/115957)
and install it as follows. Make sure to never update this plugin.

1. Go to “Settings/Preferences” → “Plugins”.
2. Click the gear icon and select “Install Plugin from Disk”.
3. Navigate to the downloaded ZIP file and select it.

After installing the plugin, format your code automatically by applying the following settings:

1. Go to “Settings/Preferences” → “Other Settings” → “google-java-format Settings”.
2. Tick the checkbox to enable the plugin.
3. Change the code style to “Default Google Java style”.
4. Go to “Settings/Preferences” → Editor → Code Style → Scala.
5. Change the “Formatter” to “scalafmt”.
6. Go to “Settings/Preferences” → “Tools” → “Actions on Save”.
7. Under “Formatting Actions”, select “Optimize imports” and “Reformat file”.
8. From the “All file types list” next to “Reformat code”, select Java and Scala.

### Updating Configuration Documentation

If you modify ConfigOptions, please regenerate the configuration documentation by running:

```shell
UPDATE=1 ./mvnw test -pl amoro-ams -am -Dtest=ConfigurationsTest
```

### Copyright

All files (including source code, configuration files) in the project are required to declare
CopyRight information at the top, and the project uses Apache License 2.0 You can configure the
copyright information in IntelliJ IDEA with the following steps:

1. Open Settings → Editor → Copyright → Copyright Profiles.
2. Add a new copyright file named Apache.
3. Add the following text as the license text:

```
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and 
limitations under the License.
```

4. Go to Editor → Copyright and select the Apache copyright file as the default copyright file for
   the project.
5. Click Apply to save the configuration changes.
6. Right-click on the existing File/Package/Module and select `Update Copyrights…` to update the
   Copyright of the file.

## Getting Help

For any questions, concerns, or queries:

- Start a discussion on our [Slack](https://olake.io/slack/) channel.
- Browse [GitHub Discussions](https://github.com/datazip-inc/olake/discussions) for ongoing conversations.
- Do not hesitate to open an [issue](https://github.com/datazip-inc/olake-fusion/issues/new) if something is unclear.

We look forward to your feedback and contributions to improve this project.
