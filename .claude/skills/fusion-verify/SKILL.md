---
name: fusion-verify
description: Stage 4 of the Fusion feature SOP. Tests an implemented change end to end (compile, module tests, server and optimizer from source when relevant), reviews the diff against the design doc and dev/sop/fusion-invariants.md for P0/P1 issues only, fixes them and repeats until none remain, then writes dev/design-docs/<folder>/ai-review.md with evidence and a draft PR description. Use when the user asks to verify, test and review a Fusion change before developer review.
argument-hint: dev/design-docs/<issue>-<slug>
---

# Fusion verify and first review (SOP stage 4)

You are the first reviewer of code written in stage 3. Your goal: when you hand over, there is no
P0/P1 left, and every claim in `ai-review.md` is backed by a command you ran.

Read first: the feature's `design.md` and `test-plan.md`, `dev/sop/fusion-invariants.md`,
`dev/sop/README.md` (severity definitions, commands), `CLAUDE.md` section 5 (how to run things).

## Input

`$ARGUMENTS` is the feature folder. The diff under review is `git diff $(git merge-base HEAD origin/staging)`
plus uncommitted changes. If the merge base cannot be found, ask which base branch to use.

## Step 1: Test

Commands are in `dev/sop/README.md`, "Commands used by the stages". Record every command and its
result for `ai-review.md`.

1. `test-compile` every touched module, and the integration suite if it changed.
2. Run all tests of each touched module, not only the new ones. A failing test that also fails on
   the base branch is noted as pre-existing (prove it by running it on the base), not ignored.
3. Check that every test in `test-plan.md` exists and passes, and that no stub from stage 2 remains
   (`grep -rn "not implemented"` in the touched modules).
4. Pick the check that proves the behaviour (`CLAUDE.md` section 5, "Pick the optimal check"):
   - Server behaviour (scheduling, commit, REST API, config, persistence): run the server from
     source against a throwaway database, drive it through the API, and check the server log,
     Postgres and MinIO.
   - Optimizer-side code: rebuild the module jar, swap it into `dist/src/main/amoro-bin/lib/`, start a
     local optimizer, and compact a real table.
   - Integration tests only if the developer asks.
   Exercise the main path and the riskiest edge cases from `design.md`.
5. Clean up as `CLAUDE.md` section 5 "Clean up" says (stop processes, drop the database, restore
   jars, delete optimizer logs). Leave no temporary code in the tree.

## Step 2: Review

Review the diff with a fresh eye, as if someone else wrote it. Look only for P0/P1 (definitions in
`dev/sop/README.md`). Go through, in this order:

1. **Design conformance**: every requirement and edge case in `design.md` is implemented as stated;
   nothing extra was added.
2. **Invariants**: each section of `fusion-invariants.md` the change touches. Data correctness of
   commits and rewrites, state machine exits on every failure path, restart at any point,
   concurrent OLake writes, optimizer/server version skew, migrations.
3. **Failure paths**: for each new call that can throw or time out, what state is left behind.
4. **Contracts**: REST, properties, config, env vars, thrift. Grep OLake-UI and OLake-Helm for each
   changed name.
5. **Security**: secrets in logs or responses, auth on endpoints, input validation.
6. **Performance**: per-file or per-table loops calling the object store or Postgres, full scans,
   unbounded memory.
7. **Tests**: do they test the behaviour, or only that code runs? Would they fail if the feature
   broke?

For every finding, challenge it before keeping it: give at least two reasons it might not be a
real problem (handled by a caller, unreachable in Fusion's scope, guarded by config), and name the
code that would change your mind. Keep it only if it survives, and cite `path:line`.

## Step 3: Fix and repeat

Fix each surviving P0/P1 with the smallest change, add or extend a test that would have caught it,
and go back to Step 1. Stop when a full pass finds no P0/P1. If a fix needs a design change, do not
make it: stop, report it, and ask the developer to update `design.md` (back to stage 1).

If after three passes new P0/P1 keep appearing, stop and report: the design or approach probably
needs rework.

## Step 4: Write `ai-review.md`

```
# AI review: <feature>

Date: <YYYY-MM-DD>   Base: <base commit>   Head: <commit or "uncommitted">
Open P0/P1: <n>

## Checks run
| Check | Command / method | Result |
| --- | --- | --- |

## Requirement coverage
| ID | Code | Test | Verified by |
| --- | --- | --- | --- |

## Findings
| # | Severity | Location | Finding | Fix | Test added |
| --- | --- | --- | --- | --- | --- |

## Residual risks
What was not verified, and why (needs Spark on Kubernetes, needs real cloud storage, ...). The
developer covers these in the stage 5 manual test.

## Contract impact
OLake-UI / OLake-Helm / database / optimizer compatibility, or "None".

## Draft PR description
Title (Conventional Commits): <type(scope): description>
<summary, change log by module, how it was tested - for the developer to edit>
```

## Output

In the chat: the `ai-review.md` path, `Open P0/P1: <n>`, the checks run with results, and the
residual risks. If `Open P0/P1` is not 0, the stage is not done; say what blocks it. Next step: the
developer's line-by-line review and manual test (stage 5 in `dev/sop/README.md`).
