---
name: fusion-verify
description: Proves an implemented Fusion change works before a PR is raised - runs the deterministic SOP checks, the full suites of touched modules, red-green proofs, and the server or optimizer from source when the behaviour needs it, then writes dev/design-docs/<folder>/verify.md with evidence, residual risks, a manual test script and the PR description draft. Does not review the code; the AI review runs on the PR. Stage 4 of the Fusion feature SOP. Use when the user asks to verify or test a Fusion change before raising its PR.
argument-hint: <Confluence URL | design doc .md | dev/design-docs/<issue>-<slug>>
---

# Fusion verify (SOP stage 4)

**Iron law: no claim without fresh evidence.** Every "passes" or "works" in your output and in
`verify.md` is backed by a command you ran in this stage, with its result. "Should", "probably"
and "looks fine" are not evidence.

This stage proves the change works. It is not the review: the one AI review is
`/fusion-pr-review`, on the PR (stage 6).

Read first: the feature's design doc (local `design.md` snapshot, kept fresh as `dev/sop/README.md`
"Design doc input" says), `test-plan.md`, `plan.md`; `dev/sop/README.md` ("Commands");
`CLAUDE.md` section 5 (running the server and optimizer from source, cleaning up).

## Input

The same input as `/fusion-write-tests` (Confluence URL, Markdown file or folder). The change is
the diff from `git merge-base HEAD origin/staging` plus uncommitted changes. If that base does not
resolve, ask.

## Progress

```
- [ ] 1. Deterministic checks (check-sop.sh)
- [ ] 2. Tests: full module suites, red-green proof
- [ ] 3. Runtime check when the behaviour needs it
- [ ] 4. verify.md written, clean-up done
```

## 1. Deterministic checks

Run `dev/sop/check-sop.sh <folder> --base origin/staging`. Fix every FAIL except the
`ai-review.md` check, which stage 6 closes.

## 2. Tests

1. `test-compile` every touched module, and the integration suite if it changed.
2. Run **all** tests of each touched module. A green run of the new tests is not a green suite.
   Name every failure. A failure that also happens on the base branch is pre-existing: prove it by
   running it there, and list it.
3. Every test in `test-plan.md` exists and is Green.
4. **Red-green proof** for each test that guards a bug fix: revert the fix, see the test fail,
   restore it, see it pass. Record both runs.

## 3. Runtime check

Pick the check that proves the behaviour (`CLAUDE.md` section 5, "Pick the optimal check"):

- Server behaviour (scheduling, commit, REST, config, persistence): server from source on a
  throwaway database, driven through the API; check the log, Postgres and MinIO.
- Optimizer-side code: package the module, swap the jar into `dist/src/main/amoro-bin/lib/`, start a
  local optimizer, compact a real table, compare rows and files before and after.
- Integration tests that `architecture.md` section 8 requires for this change: run each with
  `make integration-test TEST_ONLY=<Class#method>` and record the result. Others only if the
  developer asks.

Exercise the main path and the riskiest edge cases (E rows). Clean up as `CLAUDE.md` section 5
says: stop processes, drop the database, restore jars, delete optimizer logs, no temporary code.

A failure here goes back to `/fusion-implement`; a design gap goes back to stage 1 (the developer
updates Confluence).

## 4. `verify.md`

```
# Verify: <feature>
Date: <YYYY-MM-DD>   Base: <commit>   Head: <commit or "uncommitted">

## Checks run
| Check | Command | Result |

## Requirement coverage
| Design ID | Code (path:line) | Test | Verified by |

## Residual risks
What was not verified and why (Spark on Kubernetes, real cloud storage, scale, ...).

## Manual test script (stage 7)
Exact steps and commands the developer runs on their own build for the main path and the riskiest
edge cases, with the expected result of each. The demo video is recorded while running it.

## Contract impact
OLake-UI / OLake-Helm / docs / database / optimizer compatibility, or "None".

## PR description draft
Title: <type(scope): description>
Summary, change log by module, how it was tested, Confluence link, companion PRs needed.
Public text: no secrets, customer data or internal details beyond the link.
```

## Red flags: stop if you think any of these

| Thought | Reality |
| --- | --- |
| "The new tests pass." | Run the full module suites. |
| "That failure is unrelated." | Prove it on the base branch, then list it. |
| "The runtime check is too much setup." | If the behaviour depends on the server or optimizer, it is the proof. |
| "Minor risk, I'll leave it out." | Residual risks are listed; the developer decides. |

## Output (in the chat)

`verify.md` path, checks run with results, residual risks. Ask the developer to tick
"4 Verified" on the Confluence page. Next step: `/fusion-raise-pr <folder>`.
