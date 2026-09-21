# v446 — Feature 8 (join/leave row avatar): L2 peek on rebind
Audit of all 8 avatar features after v442–v445: #8 was the only one still doing per-bind avoidable work
(clear-then-set avatar, `eventUid|messageId` tag String concat, capturing lambda, even on an L2 hit).
Now: `ChatAvatarBinder.peekInline()` first → set bitmap inline, tag reset to null (stale in-flight loads dropped by the existing tag check);
only an L2 miss takes the old clear + tag + `bindBitmap()` path. File: MessagePagingAdapter.java. Not compile-tested; run a normal build.
