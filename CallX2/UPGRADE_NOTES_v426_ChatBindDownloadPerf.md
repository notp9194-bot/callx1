# v426 — Chat bind / download-queue / disk-stat pass (follow-up to v425)

No visual change. Everything below is on paths that run for every visible row on chat open and on every scroll rebind.

| # | Where | Problem | Fix |
|---|-------|---------|-----|
| 1 | `MessagePagingAdapter` (image, video, audio, file binds) + `MediaCache` | `MediaCache.getCached()` = `File.exists()` + `length()` disk stat on **every** bind. Only GIF/sticker/album used the in-memory `getCachedFileFast()`. | All bind-time sites now use `getCachedFileFast()`. Made safe by `MediaCache.generation()` — bumped on `invalidate()`, `clearAll()` and LRU eviction; the positive memo is dropped when it changes. |
| 2 | `MediaCache.getRemoteSize` | Same URL asked by several rows / rebinds before the first HEAD returned = one HEAD each. | In-flight dedupe: first call sends the HEAD, others join its waiter list. Same callbacks, same error strings. |
| 3 | `MediaDownloadQueue` | One **thread per waiting task** (cached pool blocked on a fair Semaphore / offline lock) — 30 undownloaded photos = ~27 parked threads. | Pending deque + small dispatcher; only the ≤3 running downloads hold a thread. Same public API, same "slot held until `markComplete`" contract, same offline pause/resume, FIFO kept. |
| 4 | `MessagePagingAdapter#onViewRecycled` + queue | Fast fling queued downloads for rows nobody sees. | New `MediaDownloadQueue.cancelPending(url)`; a recycled row drops its **not-yet-started** auto-download and clears its "downloading" marker. Started downloads keep running. |
| 5 | `MessagePagingAdapter` image branch | Received photo ThumbHash: envelope decrypt still ran on the **main thread** on first bind (v425 only cached it) — breaks the v375 "no envelope decrypt on main" rule / per-partner FIFO ratchet order. | Cache peek at bind; miss → `resolveImageBlurHashAsync()` on the partner's `E2eeDecryptExecutor` bucket, result cached. |
| 6 | `ChatThemeManager.getTextColor` | `Resources.getColor()` theme resolve on every bubble bind. | Both colors memoized in an immutable volatile holder keyed by `uiMode` (dark/light switch re-resolves). |
| 7 | `app/src/main/baseline-prof.txt` | New hot methods (v425/v426) had no AOT rule. | Hand-written rules added. Regenerate with `:macrobenchmark:generateBaselineProfile` when a device is available. |

## Bug fixed on the way
`resolveFullMediaKeyAsync()` dropped its callback when the row was recycled during the async decrypt, but the caller had already put the URL into `downloadingMediaUrls`. That URL then stayed "downloading" forever → the next bind showed a spinner with no download behind it (E2E photos after a fast scroll). The callback now gets an `onStale` hook that removes the marker.

## Files changed
- `feature-chat/.../conversation/MessagePagingAdapter.java`
- `feature-chat/.../conversation/controllers/MediaDownloadQueue.java`
- `core/.../utils/MediaCache.java`
- `core/.../utils/ChatThemeManager.java`
- `app/src/main/baseline-prof.txt`

## Verify after building
1. Open a chat with many undownloaded received photos (WiFi auto-download on): all load, max 3 at a time.
2. Fling through the chat quickly, stop: rows that were skipped still download when you stop on them (no permanent spinner).
3. Turn network off/on during downloads: queue pauses and resumes.
4. Settings → clear media cache, reopen chat: photos show the download pill again (not a blank/broken image).
5. E2E received photo shows its ThumbHash placeholder (a moment later than before is expected — it is decrypted off the main thread now).
6. Switch dark/light theme with a chat open: received/sent text colors correct.

Built without Gradle/Android SDK: Java was syntax-checked with javac's parser (only missing-Android-symbol errors remain), XML validated. Please run a normal build + the checklist above.
