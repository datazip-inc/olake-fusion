---
name: fusion-user-check
description: Checks a Fusion feature from the end user's point of view - whether failures are visible and understandable in OLake-UI, whether there are UX problems, whether users can use it on their own, and which docs, UI copy or OLake-UI changes are needed. Runs on the design doc (design mode, stage 1) or on a running build (build mode, stage 7) and writes user-check.md. Use when the user asks for a user, customer, product or UX check of a Fusion feature, or asks whether docs are needed.
argument-hint: <Confluence URL | design doc .md | dev/design-docs/<issue>-<slug>> [design | build]
---

# Fusion user check (stage 1 and stage 7)

**Iron law: judge only what the user can see.** OLake-UI screens, the API responses behind them,
and the docs. A failure that only appears in a server log, Postgres or a stack trace is invisible to
the user.

This is a product check, not a code review; it does not replace `/fusion-pr-review`. Its "Docs and UI
changes needed" list is what `/fusion-raise-pr` turns into companion PRs.

Read first: the design doc (snapshot as `dev/sop/README.md` "Design doc input" says), its section 3
(User-facing behaviour) and section 7 (Edge cases); `CLAUDE.md` section 4 (how OLake-UI calls Fusion,
terminology, defaults).

## Input

`$ARGUMENTS`: the design doc input, then `design` (default) or `build`. OLake-UI and docs paths come
from `CLAUDE.md` section 3; if they are not set, ask for them and say which checks were skipped.

## Users

- **Data engineer** in OLake-UI: enables optimization, sets Lite / Medium / Full schedules and target
  size (Maintenance pages, bulk edit), reads the table list and run history.
- **Operator** deploying with Docker or Helm: config, env vars, logs, metrics.

## Steps

```
- [ ] 1. User journey
- [ ] 2. Failure visibility
- [ ] 3. UX problems
- [ ] 4. Self-serve and docs
- [ ] 5. Build mode only: observe the real responses
- [ ] 6. Challenge each finding
```

1. **Journey.** For each scenario in section 3: how the user finds the feature, sets it up, learns
   that it worked, learns that it failed, and recovers. One row per step.
2. **Failure visibility** (the most important step). For every failure the user can be affected by
   (section 7 rows, errors in section 3, failed or skipped runs), trace where it surfaces:
   Fusion state, then the API field (run `status`, `failReason` and `summary` of the optimizing
   process, task `failReason`, table details, health score), then what OLake-UI's backend keeps
   (`server/internal/services/optimization/table.go`: the table list keeps only `status`,
   `finishTime` and `runId` of the latest run; other paths are proxied unchanged), then the screen.
   Answer for each failure: does the user see it, how long after it happens (crons run hourly or
   less often), is the message plain language with cause and next action, with no stack trace or
   secret, and can they retry or fix it themselves?
3. **UX problems.** Discoverability; safe defaults; OLake-UI terms (Lite, Medium, Full, Run) instead
   of code terms; units (UI in MB, property in bytes); invalid input rejected when saved, not at the
   next run; feedback after saving (table settings go through the async terminal API); states that
   confuse (pending for hours, skipped without a reason, "failed" without why); bulk edit; actions
   that rewrite data asking for confirmation; consistency with existing screens.
4. **Self-serve and docs.** Could a new user succeed from the UI and docs alone? List what is needed:
   olake-docs pages (user guide, configuration reference, troubleshooting), release notes, UI copy or
   tooltips, and OLake-UI changes (a field to show, a validation, a message). Check section 3 has
   enough for product to write the docs.
5. **Build mode.** On a running build (server from source per `CLAUDE.md` section 5, or the stack),
   trigger the main success path and the main failures, call the endpoints OLake-UI uses
   (`/api/ams/v1/...`, and `localhost:8000/api/opt/v1/...` when OLake-UI runs), and record exactly what
   the user would see. Compare with section 3.
6. **Challenge.** For each finding give two reasons it might be fine (shown on another screen,
   covered by existing docs, out of scope) and check them. Drop what you disprove.

**Blocking** means: a failure the user cannot see or understand, a feature the user cannot use
without asking us, a data-changing action with no confirmation, or a misleading message.
Everything else is **Should fix**.

## Output: `<folder>/user-check.md`

```
# User check: <feature>
Mode: design | build   Date: <YYYY-MM-DD>   Design doc: <source line>
User blocking: <n>

## Journey
| Step | User does | User sees | OK? |

## Failure visibility
| Failure (E/R) | Where the user sees it | Message | What they can do | Verdict |

## UX issues
| # | Blocking / Should fix | Issue | Suggestion |

## Docs and UI changes needed
| Where (olake-docs page, release notes, UI copy, OLake-UI change) | What |

## Questions for the developer or product
```

In the chat: the path, `User blocking: <n>`, the blocking items, and the docs and OLake-UI changes
list. Design changes go to the Confluence page (section 3 or 7) by the developer; OLake-UI changes
become linked issues. Next step: in design mode, `/fusion-write-tests`; in build mode, continue the
stage 7 checklist.
