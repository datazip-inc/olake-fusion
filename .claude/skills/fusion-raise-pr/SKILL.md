---
name: fusion-raise-pr
description: Raises the pull requests for a verified Fusion feature from the developer's GitHub account - the Fusion PR to staging, plus companion PRs (code and docs) in OLake-UI, OLake-Helm, olake-docs or other repos the design doc, user check or contract impact call for. Makes the companion changes in each repo following its own conventions and checks, opens every PR as a draft, and cross-links them. Stage 5 of the Fusion feature SOP. Use when the user asks to raise, open or create the PR(s) for a Fusion feature, or companion PRs in related repos.
argument-hint: <Confluence URL | design doc .md | dev/design-docs/<issue>-<slug>>
---

# Fusion raise PRs (SOP stage 5)

**Iron law: nothing leaves the machine unconfirmed.** Pushes and PRs go out under the developer's
account. Show the full plan (repos, branches, commits, PR titles and bodies) and get one explicit
go-ahead before the first push. Never push to `master`, `main` or `staging`, never force-push,
never merge. Every PR is a draft until the developer's review (stage 7).

Read first: the feature's `verify.md`, `user-check.md` ("Docs and UI changes needed") and design doc
section 6 ("OLake-UI / OLake-Helm changes needed"); `.github/pull_request_template.md`;
`CLAUDE.md` sections 3 and 4 (related repos, how OLake-UI calls Fusion).

## Input

The same input as `/fusion-write-tests`. Stop if `verify.md` is missing or lists a failing check:
run `/fusion-verify` first. Check `gh auth status`; if not logged in, ask the developer to run
`! gh auth login`.

## 1. Plan the PRs

Collect every change outside Fusion: from `user-check.md`, design doc section 6, and the contract
impact in `verify.md`. For each repo (paths from `CLAUDE.md` section 3; ask if not set):

| Repo | Base branch | Branch | Change | Why | Depends on |
| --- | --- | --- | --- | --- | --- |
| olake-fusion | `staging` | `feat/<issue>-<slug>` | the feature | design doc | - |

The base branch of another repo comes from its CONTRIBUTING or default branch. Note the order the
PRs must merge and deploy in (for example Fusion before the OLake-UI change that reads a new field).

## 2. Companion changes (code and docs)

For each other repo, in its own checkout:

1. Read its `CLAUDE.md`, `AGENTS.md` or `CONTRIBUTING.md` and follow its conventions; match its
   surrounding code. Start a branch from its up-to-date base branch; never work on the developer's
   uncommitted changes there (stop and ask if the tree is dirty).
2. Make the smallest change that delivers what the plan says: UI field or message, backend mapping
   (for example OLake-UI `mapper.go` or `table.go`), Helm values or templates, a docs page written
   from design doc section 3 (user-facing behaviour).
3. Run that repo's own checks (build, lint, tests) as its docs describe, and record the results.

## 3. Show the plan, then push

Show the developer: each repo, branch, commit list, PR title and body. After the go-ahead:

1. Fusion: commit on a feature branch with Conventional Commits messages; never commit
   `dev/design-docs/`. Run `dev/sop/check-sop.sh <folder> --base origin/staging` before pushing.
2. Push each branch and open each PR **as a draft** with `gh pr create --draft --base <base>`.
3. Fusion PR body: the template filled from the "PR description draft" in `verify.md`, the
   Confluence link, checks run, and a "Companion PRs" list. Each companion PR links back to the
   Fusion PR and states the merge and deploy order.
4. The demo video is the developer's (stage 7); leave that section marked as pending.

## Output (in the chat)

A table of the PRs opened (repo, URL, base, draft) and the merge order. Ask the developer to tick
"5 PRs raised" on the Confluence page. Next step: `/fusion-pr-review <Fusion PR number>`.
