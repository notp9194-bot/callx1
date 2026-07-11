# v116 — Reaction picker: RLottie animation not showing (root cause + fix)

## Root cause
Client-side code (v115) was correct end-to-end — manifest fetch, gzip
decompress, sha256 check, `LottieAssetCache`, `RLottieViewWrapper` all
checked out with no logic bugs.

The actual reason the quick-reaction row (long-press → the 6-emoji row
above Reply/Copy/Star/...) kept showing plain unicode glyphs instead of
the RLottie pulse animation: **`EmojiPackDownloadWorker` only ran as a
plain (non-expedited) `WorkManager` job**, enqueued from
`EmptyChatLottieController`'s constructor. Under Doze / App Standby /
battery optimization, WorkManager is legally allowed to delay a plain
`OneTimeWorkRequest` — sometimes by minutes — even though the request
itself succeeds once it finally runs. So on a fresh install / after a
force-stop, the picker was opened *before* the background sync had a
chance to complete, `LottieAssetCache` was still empty, and every slot
fell back to its (correct, by-design) unicode glyph.

The "+" full picker (Image 1, "Pick an emoji" 8-column grid) is
unicode-only by design — that was already noted as out of scope in the
v115 handoff, not a bug.

## Fix (this pass)
- `EmojiPackDownloadWorker.downloadSingleBlocking(ctx, id)` — new static
  helper: fetches (or reuses the cached) manifest, downloads/verifies
  just the one requested emoji id, returns the cached `File`.
- `MessagePagingAdapter#showActionBottomSheet` — when a quick-reaction
  slot isn't cached yet, it still shows the unicode glyph immediately
  (never blank), **and** kicks `downloadSingleBlocking` on a background
  thread for that one id. If it lands while the sheet is still open, the
  slot is swapped live from unicode → animated RLottie view, no
  re-open needed.
- Did **not** add `setExpedited()` to the background worker — a plain
  `Worker` doesn't override `getForegroundInfoAsync()`, and expedited
  work on pre-Android-12 devices needs that or it throws at runtime.
  The on-demand fetch above is the real fix and doesn't depend on
  WorkManager's scheduler at all.

## Also double-check before retesting
`Constants.SERVER_URL` points at `https://callx-server.onrender.com`.
Make sure the server zip attached alongside this one (with the
`/api/emoji-packs/manifest`, `/emoji-assets/:file`,
`/emoji-assets-gz/:file` routes and the `emoji-assets/*.json` files) is
actually the version currently **deployed and running** on Render, not
just sitting locally — if that manifest route 404s, the worker (and
now the on-demand fetch too) will just keep silently falling back to
unicode, by design, rather than crash.
