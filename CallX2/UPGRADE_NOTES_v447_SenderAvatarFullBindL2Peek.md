# v447 — Sender avatar (run-tail) full-bind path: L2 peek
v445 added `ChatAvatarBinder.peekInline()` to the payload path (`bindGroupSenderOnly`) but missed the full-bind path in
`bindCanvasMessage()` — the one that runs on every scroll bind of a run-tail row. It still built a capturing lambda per bind
even on an L2 hit. Now: `peekInline()` first → `setGroupSenderAvatarBitmap()` inline; only an L2 miss falls back to
`bindBitmap()` with the unchanged `canvasBindToken` recycle guard. File: MessagePagingAdapter.java.
Not compile-tested (no Android SDK/Gradle) — brace balance checked only; run a normal build.
