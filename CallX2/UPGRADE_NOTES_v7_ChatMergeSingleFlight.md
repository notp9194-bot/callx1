# v7 Chat Merge + Media Single-Flight Performance

## Included

### 1. Room merge N+1 query removal

`MessageDao.mergeIncomingMessages()` now collects the incoming IDs, loads
existing rows with one `WHERE id IN (...)` query, and resolves the same
conflict rules from an in-memory ID map before the existing bulk insert.

This keeps delete/edit/status/file-preservation semantics unchanged while
removing one Room round-trip per Firebase message in a replay or delta burst.

### 2. Global media download single-flight

`MediaCache.get()` and `getWithProgress()` now share one in-flight operation
per cache URL + decrypt key + expected digest. Viewer, bubble, preload, and
manual-download callers join the first download and each receive their own
completion/progress callbacks.

Delivery-transform URLs still use the stable cache URL as the identity, so
only one file is written for the same media item.

## Validation

No Gradle build or automated test was run, per request.