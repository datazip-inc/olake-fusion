# Feature Development SOP

For every feature, improvement and non-trivial bug fix. Trivial changes (typos, comments, version
bumps) skip stages 1 to 4.

## Flow

| # | Stage | Who | How | Done when |
| --- | --- | --- | --- | --- |
| 1 | Design doc | Developer | Confluence page from [the template](design-doc-template.md); `/fusion-design-check`; `/fusion-user-check` | Verdict READY; `User blocking: 0`; approved by a second developer (Full docs) |
| 2 | Tests | AI | `/fusion-write-tests` | Developer approved the test plan; every test fails for the right reason |
| 3 | Code | AI | `/fusion-implement` | All tests pass; full module suites pass |
| 4 | Verify | AI | `/fusion-verify` | `verify.md`: all checks pass, runtime proof done |
| 5 | Raise PRs | AI, developer's GitHub account | `/fusion-raise-pr` | Draft PRs open: Fusion to `staging`, plus companion PRs (OLake-UI, Helm, docs, ...) with code |
| 6 | AI review | AI | `/fusion-pr-review` (the only AI review) | `ai-review.md` says `Open P0/P1: 0`; summary comment on the PR |
| 7 | Developer review | Developer | [Checklist](#stage-7-developer-review) | Every line understood; manual test and demo video; PRs marked ready |
| 8 | Human review | Second developer | [Checklist](#stage-8-human-review); author answers with `/fusion-address-review` | Approved; all P0/P1 resolved |
| 9 | Merge | Maintainer | GitHub, in the order the PRs state | Merged; design doc status Done |

A failed gate goes back, not forward. A design gap found later goes back to stage 1: fix the
Confluence page, then rerun the affected stages. Pushes and PRs from AI go out under the
developer's account only after the developer confirms the plan; all PRs start as drafts.

## Design doc input

The design doc lives on Confluence and is never committed. Give any skill one of:

- a **Confluence URL**: read through an Atlassian / Confluence MCP tool if one is connected;
  otherwise the skill asks for Markdown;
- a **path to a Markdown file** (an export of the page);
- **Markdown pasted** into the chat.

The skill saves it as `dev/design-docs/<issue>-<slug>/design.md` with the first line
`<!-- source: <URL or file> | version or date: <...> -->`, and works there. That folder is
git-ignored; it also holds `user-check.md`, `test-plan.md`, `plan.md`, `verify.md` and
`ai-review.md`. Before each stage the skill
asks whether the page changed (or compares the version through MCP) and refreshes the snapshot.
Skills never edit Confluence.

## Gates and severity

- `dev/sop/check-sop.sh <folder> [--base origin/staging]` checks the mechanical gates: sections,
  placeholders, open questions, all 16 edge cases answered, every requirement and edge case traced
  to a test, tests exist, user check and AI review closed, no stubs, no design docs in the diff,
  Datazip headers.
- **P0:** data loss or corruption, wrong commit, secret leak, stuck or crashed server or optimizer,
  broken contract. **P1:** wrong behaviour on a realistic path, missing failure path, serious
  regression, requirement without a meaningful test, a code smell from `architecture.md` section 7,
  or a better implementation that already exists. Both block the merge.

## References

| File | Use |
| --- | --- |
| [design-doc-template.md](design-doc-template.md) | Design doc (copy to Confluence) |
| [test-plan-template.md](test-plan-template.md) | Test plan (stage 2) |
| [architecture.md](architecture.md) | How Fusion is built; where each change goes; touch the core as little as possible |
| [fusion-invariants.md](fusion-invariants.md) | What every change must keep true |
| [review-guide.md](review-guide.md) | Review lenses, confidence filter, finding format |

## Commands

```shell
FLAGS="-o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true"   # JDK 17
./mvnw $FLAGS -q test-compile -pl <module> -am
./mvnw $FLAGS test -pl <module> -am -Dtest=<Class>[#method] -Dsurefire.failIfNoSpecifiedTests=false
./mvnw $FLAGS test -pl <module>
./mvnw -o -q spotless:apply -pl <module>
make integration-test TEST_ONLY=<Class#method>        # Docker, slow
dev/sop/check-sop.sh dev/design-docs/<folder> --base origin/staging
```

Server and local optimizer from source: `CLAUDE.md` section 5.

## Stage 7: Developer review

On the draft PRs, after the AI review:

- [ ] Read every line of the Fusion PR and the companion PRs; remove anything you cannot explain.
- [ ] Every requirement and edge case maps to code and a test (`verify.md` coverage table).
- [ ] Each residual risk in `verify.md` and `ai-review.md` is acceptable or covered by your manual
      test.
- [ ] No debug code, stubs, temporary tests or unrelated changes.
- [ ] You can say why each task's approach beats its alternative (`plan.md`), and the code has
      none of the smells in [architecture.md](architecture.md) section 7.
- [ ] Ran the manual test script from `verify.md` on your build; demo video added to the PR.
- [ ] `/fusion-user-check <folder> build`: `User blocking: 0`; every docs and UI change it lists is
      in a companion PR or a linked issue.
- [ ] PR description in your words; then mark the PRs ready for review.

## Stage 8: Human review

- [ ] Reviewer is a senior developer when the author is new to Fusion.
- [ ] Read the design doc first: is this the right design, and is there a better one?
- [ ] Gates: `check-sop.sh --base`, title, demo video shows the behaviour, docs section, companion
      PRs and merge order.
- [ ] Code against [architecture.md](architecture.md), [fusion-invariants.md](fusion-invariants.md)
      and the lenses in [review-guide.md](review-guide.md), including code quality (lens 11);
      report P0/P1 in its format.
- [ ] Spot-check the AI review comment; it is the author's claim.

## Improving the SOP

When a bug escapes, add what would have caught it, in the same PR as the fix: an edge case row in
the template, an invariant, an architecture rule, or a `check-sop.sh` check.
