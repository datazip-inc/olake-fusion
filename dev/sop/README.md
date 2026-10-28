# Feature Development SOP

Every feature, improvement and non-trivial bug fix in OLake-Fusion follows this procedure. It
splits the work between the developer, who owns the intent and the final sign-off, and the AI
assistant, which drafts tests and code and gives the first review. Each AI stage is a Claude Code
skill in `.claude/skills/`, so every developer runs the same steps the same way.

Trivial changes (typos, comments, version bumps, one-line config fixes) can skip stages 1 to 4, but
still go through stages 5 to 8.

## Overview

| # | Stage | Owner | How | Output | Gate to pass before the next stage |
| --- | --- | --- | --- | --- | --- |
| 1 | Design doc | Developer | [Template](design-doc-template.md) | `design.md` | Doc complete; `/fusion-design-check` returns no blocking gaps |
| 2 | Test cases | AI | `/fusion-write-tests` | `test-plan.md`, test code | Developer approves the test plan; tests compile and fail for the right reason |
| 3 | Implementation | AI | `/fusion-implement` | Code change | All stage 2 tests pass; touched modules compile |
| 4 | Test and first review | AI | `/fusion-verify` | `ai-review.md` | No open P0/P1 findings; all checks green |
| 5 | Line-by-line review and manual test | Developer | [Checklist below](#stage-5-developer-review-and-manual-test) | Notes, demo video | Developer can explain every changed line |
| 6 | Pull request | Developer | `.github/pull_request_template.md` | PR to `staging` | CI green |
| 7 | Second review | Reviewer (with `/fusion-pr-review`) | PR comments | Approval | All P0/P1 comments resolved |
| 8 | Merge | Reviewer / maintainer | GitHub | Merged PR | - |

A stage that fails its gate goes back, not forward. A design gap found in stage 3 or later goes back
to stage 1: update `design.md` first, then rerun the affected stages.

## Where the files live

Each feature gets one folder, committed with the change:

```
dev/design-docs/<issue-number>-<short-slug>/
  design.md       stage 1, written by the developer from the template
  test-plan.md    stage 2, written by /fusion-write-tests, approved by the developer
  ai-review.md    stage 4, written by /fusion-verify
```

For example `dev/design-docs/142-cron-jitter/`. The skills take this folder (or `design.md`
inside it) as their argument. The design doc is the source of truth: when code, tests and the doc
disagree, the doc wins or is updated on purpose.

## Commands used by the stages

Maven runs offline on JDK 17 (`JAVA_HOME` from `/usr/libexec/java_home -v 17` on macOS). The flags
below skip the web UI build, the license audit and the format check, which are not what these runs
verify:

```shell
FLAGS="-o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true"

./mvnw $FLAGS -q test-compile -pl <module> -am                  # compile, with tests
./mvnw $FLAGS test -pl <module> -am -Dtest=<Class>[#method] \
  -Dsurefire.failIfNoSpecifiedTests=false                       # run selected tests
./mvnw $FLAGS test -pl <module>                                 # all tests of one module
./mvnw -o -q spotless:apply -pl <module>                        # format before handing over
./mvnw -o -q test-compile -f integration-tests/pom.xml          # compile the integration suite
make integration-test TEST_ONLY=<Class#method>                  # run integration tests (Docker, slow)
```

Running the server from source and a local optimizer is described in `CLAUDE.md` section 5.

## Stage 1: Design doc

The developer copies [design-doc-template.md](design-doc-template.md) into the feature folder as
`design.md` and fills in every section. Sections that do not apply say `N/A` and why; an empty
section is not allowed. The doc must name:

- The problem and the user-visible behaviour, using OLake-UI terms where users see them (Lite /
  Medium / Full) and the code terms (Minor / Major / Full) for the implementation.
- Every edge case and failure mode, each with the expected behaviour. These become test cases.
- Every contract the change touches: REST endpoints called by OLake-UI, table or catalog
  properties, config keys, env vars, Postgres schema, thrift API with the optimizer.
- What is out of scope.

Then run `/fusion-design-check dev/design-docs/<folder>`. It reads the code the doc touches and
lists gaps (missing edge cases, contracts the doc forgot, assumptions the code contradicts). Fix the
blocking ones in the doc. The developer stays the author: the skill reports, it does not rewrite the
doc.

## Stage 2: Test cases (AI)

Run `/fusion-write-tests dev/design-docs/<folder>`. The skill:

1. Writes `test-plan.md`: one row per test case, each traced to a requirement or edge case in
   `design.md`, with the level it is tested at (unit, AMS service test, integration test).
2. Stops for the developer to approve the plan. Missing or wrong cases are fixed here, before any
   test code exists.
3. Writes the tests, plus the smallest compile-only stubs for new types or methods the tests call.
4. Runs them and confirms they fail because the feature is missing, not because the test is broken.

These tests stay in the final change.

## Stage 3: Implementation (AI)

Run `/fusion-implement dev/design-docs/<folder>`. The skill implements the design in the existing
architecture, checks each change against [fusion-invariants.md](fusion-invariants.md), and loops
until every stage 2 test passes. It does not edit stage 2 tests to make them pass; if a test is
wrong, it stops and says so, and the developer decides (and updates `test-plan.md`).

## Stage 4: Test and first review (AI)

Run `/fusion-verify dev/design-docs/<folder>`. The skill:

1. Compiles, formats (spotless) and runs the tests of every touched module.
2. Runs the server from source and, for optimizer-side changes, a local optimizer, as described in
   `CLAUDE.md` section 5, when the change depends on them.
3. Reviews the diff against the design doc and the invariants, looking only for P0/P1 issues, and
   challenges each finding before reporting it.
4. Fixes the P0/P1 findings and repeats from step 1, until none are left.
5. Writes `ai-review.md`: what was run with the results, findings and their fixes, residual risks,
   and a draft PR description.

The stage is done only when `ai-review.md` says `Open P0/P1: 0`.

Severity used by all skills and reviewers:

- **P0**: data loss or corruption, a wrong commit to an Iceberg table, security or secret leak,
  crash or deadlock of the server or optimizer, a broken contract with OLake-UI or a deployed
  database. Must be fixed before merge.
- **P1**: wrong behaviour on a realistic path (including the edge cases in the design doc), missing
  failure handling, a serious performance regression, a missing test for a stated requirement. Must
  be fixed before merge, or explicitly accepted by the reviewer with a follow-up issue.
- P2 and below are not reported by the AI stages.

## Stage 5: Developer review and manual test

The AI has reviewed its own work; the developer now owns it. Before raising a PR:

- [ ] Read every line of the diff (`git diff staging...HEAD`). You can explain why each line exists.
      Anything you cannot explain is removed or rewritten.
- [ ] Every requirement and edge case in `design.md` maps to code and a test in `test-plan.md`.
- [ ] Each P0/P1 fix in `ai-review.md` is really fixed, and each "residual risk" is acceptable.
- [ ] No leftover debug code, temporary tests, stubs, TODOs without an issue, or unrelated changes.
- [ ] Contracts: if an endpoint, property, config key, env var or schema changed, OLake-UI and
      OLake-Helm were checked (see `CLAUDE.md` section 3) and the impact is in the PR description.
- [ ] Manual test on your own build (`make setup-debug-mode`, or `make start-fusion-docker` for the
      full stack), covering the main path and the riskiest edge cases. Record the demo video
      required by `CONTRIBUTING.md` while you do it.
- [ ] Modified files that carry the Apache header have `Modified by Datazip Inc. in <year>`.

## Stage 6: Pull request

- Target `staging`. Title in Conventional Commits form, for example `feat(ams): add cron jitter`.
- Start from the draft PR description in `ai-review.md`, edit it into your own words, and fill in
  the PR template, including the demo video and the documentation section.
- Link the issue and the feature folder (`dev/design-docs/<folder>/design.md`).

## Stage 7: Second review

The reviewer reads `design.md` first, then the PR. Run `/fusion-pr-review <PR number>` to get an
independent P0/P1 review against the design doc and invariants; it does not see stage 4's reasoning,
so it is a second opinion, not a repeat. The reviewer decides which of its findings to post. The
human reviewer also checks the things the AI cannot judge: whether the design is the right one,
and whether the demo video shows the described behaviour.

Review comments that change behaviour go back through stages 1 to 4 for the affected part (update
the doc, the tests, the code, rerun `/fusion-verify`).

## Stage 8: Merge

Merge when CI is green, all P0/P1 comments are resolved, and the reviewer approves. The feature
folder stays in the repo as the record of why the change was made.
