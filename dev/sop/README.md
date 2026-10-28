# Feature Development SOP

Every feature, improvement and non-trivial bug fix in OLake-Fusion follows this procedure. The
developer owns the intent and the final sign-off; the AI drafts tests and code and gives the first
review. Each AI step is a Claude Code skill in `.claude/skills/`, so everyone runs it the same way,
and `check-sop.sh` checks the mechanical gates so nobody has to by hand.

## Overview

| # | Stage | Owner | How | Output | Gate |
| --- | --- | --- | --- | --- | --- |
| 1 | Design doc | Developer | [Template](design-doc-template.md); `/fusion-design-check [interview]` | `design.md` | Check verdict READY; developer approves |
| 2 | Test cases | AI | `/fusion-write-tests` | `test-plan.md`, tests | Developer approves the plan; every test red for the right reason |
| 3 | Implementation | AI | `/fusion-implement` | `plan.md`, code | All tests green; full module suites pass |
| 4 | Test and first review | AI | `/fusion-verify` | `ai-review.md` | `Open P0/P1: 0`; `check-sop.sh --base` passes |
| 5 | Line-by-line review and manual test | Developer | [Checklist](#stage-5-developer-review-and-manual-test) | Demo video | Developer can explain every line |
| 6 | Pull request | Developer | PR template | PR to `staging` | CI green |
| 7 | Second review | Reviewer | `/fusion-pr-review`; author uses `/fusion-address-review` | Approval | All P0/P1 resolved |
| 8 | Merge | Maintainer | GitHub | - | - |

A stage that fails its gate goes back, not forward. A design gap found in any later stage goes back
to stage 1: update `design.md`, then rerun the affected stages. Each stage ticks the "SOP progress"
list at the top of `design.md`, so anyone can see where a feature is.

**When to use what:**

- **Full design doc:** new behaviour, or any change to a contract (REST, properties, config, schema,
  thrift, Iceberg metadata written).
- **Lite design doc** (sections marked [Lite]): unambiguous change in one area with no contract
  change.
- **No design doc:** trivial changes (typos, comments, version bumps, one-line config fixes). Skip
  stages 1 to 4; stages 5 to 8 still apply.

## Feature folder

```
dev/design-docs/<issue-number>-<short-slug>/
  design.md       stage 1   developer
  test-plan.md    stage 2   /fusion-write-tests, approved by the developer
  plan.md         stage 3   /fusion-implement (resumable task list)
  ai-review.md    stage 4   /fusion-verify (evidence, manual test script, draft PR text)
```

Committed with the change. The design doc is the source of truth: when code, tests and doc
disagree, the doc wins or is changed on purpose.

## Shared references

| File | Used for |
| --- | --- |
| [design-doc-template.md](design-doc-template.md) | Stage 1 |
| [test-plan-template.md](test-plan-template.md) | Stage 2 |
| [fusion-invariants.md](fusion-invariants.md) | What every change must keep true; read in stages 1 to 7 |
| [review-guide.md](review-guide.md) | Severity, independent reviewers, lenses, confidence filter, finding format |
| [check-sop.sh](check-sop.sh) | Deterministic gates: sections, placeholders, open questions, traceability, tests exist, review closed, no stubs, modification headers |

## Commands used by the stages

Maven runs offline on JDK 17 (`JAVA_HOME` from `/usr/libexec/java_home -v 17` on macOS). These
flags skip the web UI build, the license audit and the format check, which these runs do not
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
dev/sop/check-sop.sh dev/design-docs/<folder> [--base origin/staging]   # SOP gates
```

Running the server and a local optimizer from source is described in `CLAUDE.md` section 5.

## Severity

Defined in [review-guide.md](review-guide.md). In short: **P0** is data loss or corruption, a wrong
commit, a secret leak, a stuck or crashed server or optimizer, or a broken contract. **P1** is wrong
behaviour on a realistic path, a missing failure path, a serious performance regression, or a
requirement with no meaningful test. Both block the merge. The AI stages report nothing below P1.

## Stage 1: Design doc

Copy the template to `dev/design-docs/<folder>/design.md` and fill it in, or run
`/fusion-design-check <folder> interview` to be asked one question at a time while you write. Then
run `/fusion-design-check <folder>`: it reads the code the doc touches and reports blocking gaps.
You stay the author; the skill never decides expected behaviour for you. Get the doc reviewed by a
second developer for Full docs before stage 2.

## Stage 2: Test cases (AI)

`/fusion-write-tests <folder>` writes `test-plan.md` with every requirement, edge case and
acceptance scenario traced to a test, and **stops for your approval**. Fix missing or wrong cases
there, before test code exists. It then writes the tests and compile-only stubs and shows each test
failing for the right reason. These tests stay in the change.

## Stage 3: Implementation (AI)

`/fusion-implement <folder>` writes `plan.md` (small tasks, each with the tests it makes green), then
implements task by task in the existing architecture, checking `fusion-invariants.md`. It never
weakens an approved test; if it thinks one is wrong, it stops and you decide. A new session can
resume from `plan.md`.

## Stage 4: Test and first review (AI)

`/fusion-verify <folder>` runs the full module suites, red-green proofs for fixes, and the server or
optimizer from source when the behaviour needs it. Fresh reviewer subagents, which see only the
design doc, invariants and diff, review it for P0/P1; confirmed findings are fixed with a test and
the loop repeats until none are left. It writes `ai-review.md`: checks run, coverage, findings,
residual risks, a **manual test script for stage 5**, and a draft PR description.

## Stage 5: Developer review and manual test

The AI has reviewed its own work; you now own it.

- [ ] Read every line of `git diff origin/staging...HEAD`. Anything you cannot explain is removed
      or rewritten.
- [ ] Every requirement and edge case in `design.md` maps to code and a test (`ai-review.md`
      coverage table); spot-check that the key tests really assert the behaviour.
- [ ] Each fix in `ai-review.md` is really fixed; each residual risk is acceptable or covered by
      your manual test.
- [ ] No leftover debug code, stubs, temporary tests or unrelated changes.
- [ ] Contract changes were checked in OLake-UI and OLake-Helm and are in the PR description.
- [ ] Run the manual test script from `ai-review.md` on your own build (`make setup-debug-mode`, or
      `make start-fusion-docker` for the full stack) and record the demo video while doing it.
- [ ] `dev/sop/check-sop.sh <folder> --base origin/staging` passes.

## Stage 6: Pull request

Target `staging`, Conventional Commits title (for example `feat(ams): add cron jitter`). Start from
the draft in `ai-review.md`, rewrite it in your own words, fill in the template including the demo
video and documentation section, and link the issue and `design.md`.

## Stage 7: Second review

The reviewer reads `design.md` first, then runs `/fusion-pr-review <PR>` for an independent P0/P1
review and the SOP gates. It is a second opinion, not a repeat: it does not trust `ai-review.md`. The
reviewer posts the findings they agree with and judges what the AI cannot: whether this is the right
design, and whether the demo video shows the behaviour.

The author handles comments with `/fusion-address-review <PR>`: each comment is checked against the
code and design before acting; valid ones are fixed with a test, wrong ones get an evidence-based
reply, and behaviour changes go back through the design doc.

## Stage 8: Merge

Merge when CI is green, all P0/P1 are resolved and the reviewer approves. Tick the last box in
`design.md`. The feature folder stays as the record of the decision.

## Improving this SOP

When a bug escapes to review or production, ask which stage should have caught it and fix that
stage: add the invariant to `fusion-invariants.md`, the scenario to the template's edge-case
prompts, or the check to `check-sop.sh` or a skill, in the same PR as the fix.

## What this is based on

| Practice | Source | Where it is used |
| --- | --- | --- |
| Context, goals and non-goals, alternatives, cross-cutting concerns; mini doc for small changes | [Design docs at Google](https://www.industrialempathy.com/posts/design-docs-at-google/) | Template sections 2, 3, 7, 10; Lite doc |
| Risks and mitigations, test plan by level, upgrade/downgrade, version skew, production readiness | [Kubernetes KEP template](https://github.com/kubernetes/enhancements/tree/master/keps/NNNN-kep-template) | Template sections 8, 10, 11, 13 |
| Summary, drawbacks, prior art, unresolved questions | [Rust RFC template](https://github.com/rust-lang/rfcs/blob/master/0000-template.md) | Template sections 1, 7, 14, 15 |
| Prioritised user stories, Given/When/Then, FR and SC IDs, `[NEEDS CLARIFICATION]` | [GitHub spec-kit](https://github.com/github/spec-kit) | Template sections 4, 5, 14; `check-sop.sh` |
| Scope, approach, environment, entry and exit criteria | IEEE 829 / ISO/IEC/IEEE 29119-3 test documentation | `test-plan-template.md` |
| Failing test first, red flags, verification before claims, written plans, review with crafted context, receiving review | [obra/superpowers](https://github.com/obra/superpowers) | All skills |
| Concise skills, third-person descriptions, one-level references, checklists, feedback loops, scripts for deterministic checks | [Anthropic skill authoring best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices) | Skill structure; `check-sop.sh` |
