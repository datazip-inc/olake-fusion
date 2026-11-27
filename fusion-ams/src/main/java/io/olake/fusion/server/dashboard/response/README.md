# `io.olake.fusion.server.dashboard.response`

The JSON envelope of every `/api/ams/v1` response (`OkResponse`, `ErrorResponse`) and the page
wrapper for list endpoints (`PageResult`).

## Role in Fusion

- Every controller in [`controller`](../controller/README.md) answers with
  `ctx.json(OkResponse.of(...))` or `ctx.json(OkResponse.ok())`.
- `DashboardServer.handleException` and the 404/500 handlers in `FusionServiceContainer` answer
  with `ErrorResponse`. `PlatformFileInfoController.uploadFile` also returns
  `new ErrorResponse("Uploaded file is not in valid XML format")`.
- OLake-UI's Go backend decodes these envelopes; for forwarded paths it wraps the whole body again
  in `{"success":true,"message":"request forwarded successfully","data":...}`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `Response` | Base: `code` (int) and `message` | |
| `OkResponse<R>` | `code` 200, `message` `"success"` (or a given message), `result`. `OkResponse.ok()` is a shared instance with `result` null | controllers |
| `ErrorResponse` | `code`, `message`, `requestId`. `new ErrorResponse(String)` / `ErrorResponse.of(String)` use code 400 and `requestId` null | `DashboardServer.handleException` |
| `PageResult<R>` | `{list, total}`. `of(list, total)` wraps an already paged list; `of(list, offset, limit)` pages in memory with `total = list.size()`; `of(list, offset, limit, convert)` also maps each item | controllers |

## How it works

- Success body: `{"message":"success","code":200,"result":<R>}`.
- Error body: `{"message":"...","code":403|500|404|400,"requestId":""}`.
- In-memory paging (`PageResult.of(list, offset, limit)`) is used by snapshots, partitions, files,
  tags, branches, tasks and optimizers; SQL paging plus `PageResult.of(list, total)` is used by
  optimizing processes and optimizer-group tables.

## State, threads and concurrency

`OkResponse.ok()` and `PageResult.empty()` return shared static instances. Their setters are
public, so code must not mutate them.

## Contracts

- The field names `code`, `message`, `result`, `requestId`, `list`, `total` come from the getters
  and are read by OLake-UI (for example `result.apikey`, `result.sessionId`,
  `result.logStatus`). Renaming any of them breaks OLake-UI.
- `code` carries the error class. `DashboardServer.handleException` does not set an HTTP status,
  so a client must check `code`, not only the HTTP status.

## Extending or changing it

Return new data through `OkResponse.of(...)`; do not add a second envelope type. For a list, page
with `PageResult` so the UI gets `list` and `total`.

## Invariants and pitfalls

- `PageResult.of(list, offset, limit)` loads the whole list before paging; for large tables
  (files, snapshots) the cost is in the descriptor call, not in the page size.
- `PageResult.empty()` is not used anywhere.

## Tests

No dedicated tests. `TestLoginAndApiToken` reads `OkResponse.getResult()`:
`./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestLoginAndApiToken -Dsurefire.failIfNoSpecifiedTests=false`

## Related packages

- [`dashboard`](../README.md), [`dashboard/controller`](../controller/README.md)
