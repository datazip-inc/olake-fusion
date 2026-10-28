---
name: fusion-verify
description: Proves an implemented Fusion change works and is free of P0/P1 issues before developer review - runs the full module tests, red-green checks, server and optimizer from source when relevant, then runs independent reviewer subagents against the design doc and invariants, fixes surviving findings and repeats until none remain. Writes dev/design-docs/<folder>/ai-review.md with evidence, a manual test script and a draft PR description. Stage 4 of the Fusion feature SOP. Use when the user asks to verify, test or review a Fusion change before developer review.
argument-hint: dev/design-docs/<issue>-<slug>
---

# Fusion verify and first review (SOP stage 4)

**Iron law: no claim without fresh evidence.** Every "passes", "fixed" or "works" in your output
and in `ai-review.md` is backed by a command you ran in this stage, with its result. "Should",
"probably" and "looks fine" are not evidence.

Read first: the feature's `design.md`, `test-plan.md` and `plan.md`; `dev/sop/review-guide.md`;
`dev/sop/fusion-invariants.md`; `dev/sop/README.md` ("Commands used by the stages");
`CLAUDE.md` section 5 (running the server and optimizer from source, cleaning up).

## Input

`$ARGUMENTS`: the feature folder. The change under review is the diff from
`git merge-base HEAD origin/staging` plus uncommitted changes. If that base does not resolve, ask.

## Progress

```
- [ ] 1. Deterministic checks (check-sop.sh)
- [ ] 2. Tests: full module suites, red-green proof
- [ ] 3. Runtime check (server / optimizer from source) when the behaviour needs it
- [ ] 4. Independent review (subagents)
- [ ] 5. Fix surviving P0/P1, then back to 1
- [ ] 6. ai-review.md written, clean-up done
```

## 1. Deterministic checks

Run `dev/sop/check-sop.sh <folder> --base origin/staging`. Fix every FAIL except the `ai-review.md`
check, which this stage closes.

## 2. Tests

1. `test-compile` every touched module, and the integration suite if it changed.
2. Run **all** tests of each touched module. A green run of the new tests is not a green suite.
   Name every failure. A failure that also happens on the base branch is pre-existing: prove it by
   running it there, and list it; do not ignore it silently.
3. Every test in `test-plan.md` exists and is Green.
4. **Red-green proof** for each test that guards a bug fix or a P0/P1 fix: revert the fix, run the
   test and see it fail, restore the fix and see it pass. Record both runs.

## 3. Runtime check

Pick the check that proves the behaviour (`CLAUDE.md` section 5, "Pick the optimal check"):

- Server behaviour (scheduling, commit, REST, config, persistence): run the server from source on a
  throwaway database, drive it through the API, check the server log, Postgres and MinIO.
- Optimizer-side code: package the module, swap the jar into `dist/src/main/amoro-bin/lib/`, start
  a local optimizer, compact a real table, and check rows and files before and after.
- Integration tests only if the developer asks.

Exercise the main path and the riskiest edge cases (E-IDs) from `design.md`. Clean up as
`CLAUDE.md` section 5 says: stop processes, drop the database, restore jars, delete optimizer
logs, leave no temporary code.

## 4. Independent review

Follow `dev/sop/review-guide.md`: fresh reviewer subagents in parallel, one per lens group (one
reviewer for a diff under about 100 lines), each given only the design doc, test plan, invariants,
review guide and the diff, never this session's reasoning. Merge their findings, remove duplicates,
apply the confidence threshold (80) and the false-positive filter.

Then verify each surviving finding yourself before acting on it: reproduce it, or read the code that
proves it. A finding you cannot confirm is dropped and listed under "Rejected findings" with the
reason.

## 5. Fix and repeat

Fix each confirmed P0/P1 with the smallest change, add or extend a test that fails without the fix
(red-green proof), and go back to step 1. Stop when a full pass finds no P0/P1.

- A fix that needs a design change: do not make it. Stop and ask the developer to update
  `design.md` (back to stage 1).
- New P0/P1 still appearing after three passes: stop. The approach likely needs rework; report it.

## 6. `ai-review.md`

```
# AI review: <feature>

Date: <YYYY-MM-DD>   Base: <commit>   Head: <commit or "uncommitted">
Open P0/P1: <n>

## Checks run
| Check | Command | Result |

## Requirement coverage
| Design ID | Code (path:line) | Test | Verified by |

## Findings
| # | Severity | Location | Finding | Fix | Test | Red-green |

## Rejected findings
| Finding | Why rejected |

## Residual risks
What was not verified and why (needs Spark on Kubernetes, real cloud storage, scale, ...).

## Manual test script for stage 5
Exact steps and commands the developer runs on their own build to see the main path and the
riskiest edge cases, with the expected result of each. Record the demo video while running it.

## Contract impact
OLake-UI / OLake-Helm / database / optimizer compatibility, or "None".

## Draft PR description
Title: <type(scope): description>
Summary, change log by module, how it was tested, design doc link.
```

Run `dev/sop/check-sop.sh <folder> --base origin/staging` once more; it must pass.

## Red flags: stop if you think any of these

| Thought | Reality |
| --- | --- |
| "I wrote it, I know it's right." | That is why the review runs in fresh subagents. |
| "The new tests pass." | Run the full module suites. |
| "That failure is unrelated." | Prove it on the base branch, then list it. |
| "Minor risk, I'll leave it out of the report." | Residual risks are listed; the developer decides. |

## Output (in the chat)

`ai-review.md` path; `Open P0/P1: <n>`; checks run with results; residual risks. If `Open P0/P1`
is not 0, the stage is not done: say what blocks it. Otherwise tick "4 AI review" in the doc's SOP
progress. Next step: the developer's line-by-line review and manual test (`dev/sop/README.md`,
stage 5).
