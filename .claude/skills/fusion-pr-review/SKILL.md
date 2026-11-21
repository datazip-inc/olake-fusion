---
name: fusion-pr-review
description: The one AI review of the Fusion SOP - reviews a raised Fusion PR and its companion PRs (OLake-UI, OLake-Helm, docs) with independent reviewer subagents against the design doc, architecture, invariants and review guide, confirms each finding, fixes confirmed P0/P1 on the PR branches with tests, repeats until none remain, writes ai-review.md and posts one summary comment. Stage 6 of the Fusion feature SOP. Use when the user asks for the AI review of a Fusion PR.
argument-hint: <Fusion PR number>
---

# Fusion PR review (SOP stage 6, the only AI review)

**Iron law: review as a stranger, prove before fixing.** Reviewers see only crafted context, never
this session's reasoning. A finding is acted on only after it is confirmed in the code or by a run.

Read first: `dev/sop/review-guide.md`, `dev/sop/architecture.md`, `dev/sop/fusion-invariants.md`,
the feature's design doc snapshot (or fetch it from the Confluence link in the PR body, as
`dev/sop/README.md` "Design doc input" says), `test-plan.md` and `verify.md`.

## Input

`$ARGUMENTS`: the Fusion PR number. Load it with
`gh pr view <n> --json number,title,body,baseRefName,headRefName,headRefOid,files` and
`gh pr diff <n>`; find the companion PRs in its "Companion PRs" list and load them the same way.

## Progress

```
- [ ] 1. Gates
- [ ] 2. Independent review (subagents)
- [ ] 3. Confirm findings
- [ ] 4. Fix on the PR branches, then back to 2
- [ ] 5. ai-review.md and PR comment
```

## 1. Gates

`dev/sop/check-sop.sh <folder> --base origin/staging` on the PR head; title in Conventional Commits
form; PR targets `staging`; no file under `dev/design-docs/` other than the README; no test-only
leftovers (`local-test/`, compose files, debug logging, disabled tests). Test levels: classify each
change in the PR with `architecture.md` section 8 and check the PR adds or updates at least the
required unit, AMS service and integration tests (`integration-tests/`, `Contract`). CI: `gh pr
checks <n>` shows the core CI and the integration-tests workflow green, not skipped. A failed gate
is P1.

## 2. Independent review

Follow `dev/sop/review-guide.md`: reviewer subagents in parallel by lens group (one reviewer for a
diff under about 100 lines), each given only the design doc, test plan, architecture, invariants,
review guide and the PR diffs. Include the companion PRs: contracts must match on both sides
(field names, types, units, error handling), and docs must match section 3 of the design doc.
Merge, deduplicate, apply the confidence threshold (80) and the false-positive filter.

## 3. Confirm

For each surviving finding, read the code that proves it or reproduce it. Unconfirmed findings are
dropped and listed under "Rejected findings" with the reason.

## 4. Fix and repeat

Fix each confirmed P0/P1 on the PR's branch, in the right repo: the smallest change, plus a test
that fails without it (red-green proof); run the touched module suites. Show the developer the
commits, then push to the same branch (never force-push). Go back to step 2 until a full pass finds
no P0/P1.

- A fix that needs a design change: stop; the developer updates Confluence (back to stage 1).
- New P0/P1 still appearing after three passes: stop and report; the approach needs rework.

## 5. Report

Write `<folder>/ai-review.md`:

```
# AI review: <feature>   PR: #<n> (+ companion PRs)
Date: <YYYY-MM-DD>   Head: <commit>
Open P0/P1: <n>

## Gates
## Findings
| # | Severity | Repo / location | Finding | Fix (commit) | Test | Red-green |
## Rejected findings
| Finding | Why rejected |
## Residual risks
```

Post one summary comment on the Fusion PR with `gh pr comment <n>` (gates, findings fixed, `Open
P0/P1`, residual risks), after showing it to the developer. No internal details beyond the
Confluence link.

## Red flags: stop if you think any of these

| Thought | Reality |
| --- | --- |
| "I wrote it, I know it's right." | That is why reviewers are fresh subagents. |
| "The companion PR is just UI, skip it." | Contract mismatches between repos are P0. |
| "This finding is probably right, fix it." | Confirm it first. |

## Output (in the chat)

`ai-review.md` path, `Open P0/P1: <n>`, findings fixed with commits, residual risks. If not 0, say
what blocks. Otherwise ask the developer to tick "6 AI review clean". Next step: the developer's
review on the PR (stage 7 in `dev/sop/README.md`).
