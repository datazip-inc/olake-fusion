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

# Contributing

This document provides guidelines for contributing to OLake-Fusion. While these suggestions are not strict
rules, they aim to facilitate a smooth contribution experience.

If you are thinking of contributing but first would like to discuss the change you wish to make,
we welcome you to join the OLake community:

- [Slack](https://olake.io/slack/)
- [GitHub Issues](https://github.com/datazip-inc/olake-fusion/issues/new)
- [Contributing Docs](https://olake.io/docs/community/contributing/)
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
    * The PR title format should be: \[{module}\] {pr_description}.
    * Add fix/resolve #{issue_number} in the description to link the PR to the issue.
    * The linked issue should clearly explain the background, objectives, and implementation methods
      of the PR.
* The change log in the PR should clearly describe the changes made in modules, classes, methods,
  etc.
* The PR should include corresponding testing methods, and the test results should be visible.
* If the PR involves new features, the user document should include instructions for its usage.

## Code review

Code review is a crucial aspect of contributing to a project, and all contributors are encouraged to
actively review and provide feedback on each other's PRs.

* Check whether the PR meet the requirements specified in the previous section on Pull Requests.
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

## Building the Project Locally

[Build Guide](https://github.com/datazip-inc/olake-fusion?tab=readme-ov-file#building) can be found in the GitHub README.

## Importing the OLake-Fusion project into IntelliJ IDEA

The following guide describes how to import the OLake-Fusion project into IntelliJ IDEA and deploy it.

### Requirements
+ Java Version: Java 17 is required.

#### Required plugins
1. Go to `Settings` → `Plugins` in IntelliJ IDEA.
2. Select the “Marketplace” tab.
3. Search for and install the following plugins:
    - Scala
4. Restart IntelliJ IDEA if prompted.

### Import OLake-Fusion into IntelliJ IDEA
This guide is based on IntelliJ IDEA 2024. Some details might differ in other versions.

1. Clone the repository and create the test configuration file:

```shell
$ git clone https://github.com/datazip-inc/olake-fusion.git
$ cd olake-fusion
$ base_dir=$(pwd)
$ mkdir -p conf
$ cp dist/src/main/amoro-bin/conf/config.yaml conf/config.yaml
$ sed -i '' "s|/tmp/amoro/derby|${base_dir}/conf/derby|g" conf/config.yaml
```
The above text replacement command is applicable to macOS. In the Linux system, `-i ''` should be replaced with `-i`.

2. Import the project:
    1. Open IntelliJ IDEA and select `File`->`Open`.
    2. Choose the root folder of the cloned OLake-Fusion repository.
3. Configure Java version:
    1. Open the `Maven` tab on the right side of the IDE.
    2. Expand `Profiles` and ensure the selected Java version matches your IDE's Java version.
    3. To check or change the project SDK, go to `File`->`Project Structure...`->`Project`->`SDK`.

4. Load dependencies

   In the `Maven` tab, click the `Reload All Maven Projects` button, or right-click the imported OLake-Fusion project in the Project view and select `Maven`->`Reload project`.

### Quickstart
   The detailed description of the relevant parameters can be found in the [OLake documentation](https://olake.io/docs/).
To quickly explore OLake-Fusion's core features, such as compaction, visit [Configure-first-compaction](https://olake.io/docs/getting-started/configure-first-compaction/).


## Code suggestions

### Code formatting

OLake-Fusion uses [Spotless](https://github.com/diffplug/spotless/tree/main/plugin-maven) together with
[google-java-format](https://github.com/google/google-java-format) to format the Java code. For
Scala, it uses Spotless with [scalafmt](https://scalameta.org/scalafmt/).

You can format your code by executing the command `dev/reformat` in the root directory of
project.

Or you can configure your IDEA to automatically format your code. Then you will need to install
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
 