# v117 — Reaction picker still unicode after server fix (stale manifest cache)

## Root cause
Server was fine after the `emoji_assets` → `emoji-assets` folder rename —
`/api/emoji-packs/manifest` now returns all 7 entries correctly (confirmed).

But `EmojiPackDownloadWorker.downloadSingleBlocking()` (the on-demand,
live-swap fetch added in v116) tried `EmojiManifestRepository
.getCachedManifest()` FIRST, and only fell through to a real network
call if that returned null. `getCachedManifest()` happily returns
whatever's sitting in SharedPrefs as long as it's under 6h old — which,
on a device that had already opened a chat once while the server folder
was still misnamed, was the broken `{"emojis":[]}` response. That stale
"valid" empty manifest then blocked every on-demand fetch for up to 6h
per device, even after the server itself was completely fixed.

(The background `EmojiPackDownloadWorker.doWork()` path was never
affected by this — it always called `fetchManifestBlocking()` directly,
so it would have self-healed on the next chat-open in the background.
The on-demand/live-swap path was the one stuck.)

## Fix
`downloadSingleBlocking()` now always calls `fetchManifestBlocking()`
directly — no `getCachedManifest()` shortcut. Still cheap: that method
sends the stored ETag via `If-None-Match`, so if the manifest genuinely
hasn't changed server-side, it's a 304 with no re-parse. But if it HAS
changed (like right after this exact kind of server-side fix), it's
guaranteed to be picked up on the very next long-press instead of
waiting up to 6h.

No server changes in this pass — the server fix (folder rename) from
the previous message is correct and stays as-is.
