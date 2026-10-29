# Test plan: <feature title>

| | |
| --- | --- |
| Design doc | design.md (last updated <YYYY-MM-DD>) |
| Status | Draft / Approved by <developer> on <YYYY-MM-DD> |

## 1. Scope

**In scope:** the requirements, stories and edge cases of `design.md` this plan covers.

**Out of scope:** what is deliberately not tested, and why.

## 2. Approach

Levels follow `dev/sop/architecture.md` section 8: at least the levels each change's row requires.

| Level | Where | Used for |
| --- | --- | --- |
| Unit | `<module>/src/test/...` | |
| AMS service | `amoro-ams/src/test/...` (`AMSServiceTestBase`, `AMSTableTestBase`, ...) | |
| Integration | `integration-tests/` (`ItBase`) | |
| Manual (stage 7, demo video) | Server from source / full stack | |

## 3. Test cases

| ID | Covers | Level | Test (`Class#method`) | Given / When / Then | Status |
| --- | --- | --- | --- | --- | --- |
| T1 | R1, E3 | Unit | `TestFoo#rejectsNegativeSize` | Given ..., when ..., then ... | Planned |

`Covers` lists design doc IDs (R, E). `Status` moves Planned, Red (fails for the
right reason), Green (passes with the implementation).

## 4. Traceability

Every requirement (R) and edge case (E) of `design.md` not marked N/A appears here, either covered by a test or listed
with a reason.

| Design ID | Tests | Not covered because / checked instead by |
| --- | --- | --- |
| R1 | T1 | |

## 5. Stubs for stage 3

Compile-only stubs added so the tests compile. Stage 3 replaces all of them.

| Stub | File |
| --- | --- |

## 6. Environment

JDK 17, offline Maven, Docker for integration tests. Any data, fixtures or config the tests need.

## 7. Entry and exit criteria

- **Entry (start stage 3):** plan approved; every test Red for the right reason; stubs listed.
- **Exit (end stage 4):** every test Green; full module suites pass; no stub left; red-green proof
  for each regression test recorded in `verify.md`.
