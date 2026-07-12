# v124 — Reaction picker: REAL animated RLottie emoji (bundled, not downloaded)

## Why v123 wasn't the actual fix
v123 correctly diagnosed the mismatch bug and made the picker un-mismatched
by falling back to plain unicode glyphs + a bounce animation. That's honest,
but it isn't what was asked for — real animated reaction stickers, Telegram-
style. This pass delivers that.

## What changed

### 1. Real emoji artwork (not placeholder dots)
`emoji-assets/{heart,thumb,laugh,wow,sad,angry}.json` on the server were
each a single flat-colored ellipse (see v123 notes for the exact colors —
that's why the picker showed circles). They're now genuine hand-built
vector Lottie animations:

| id     | what it draws | loop animation |
|--------|----------------|-----------------|
| heart  | red heart (2 lobes + rotated-square point) | heartbeat double-pulse |
| thumb  | thumb + fist (rounded rects, skin tone) | nodding wiggle |
| laugh  | yellow face, closed happy eyes, open mouth, blue tears | side-to-side giggle shake + tears dropping/fading |
| wow    | yellow face, raised brows, round eyes, "o" mouth | gasp gap (mouth scale pulse) + pop-in |
| sad    | yellow face, worried brows, frown, blue tear | slow droop bob + tear falling/fading |
| angry  | orange-red face, angled brows, frown, red anger marks | fast vibration shake + marks flashing |

All built from plain shape primitives (ellipse/rounded-rect groups with
animated position/rotation/scale/opacity keyframes) — no external art
files needed, and every animated keyframe carries the `e`/`i`/`o` fields
`LottieJsonValidator` requires, so nothing here can trigger the native
SIGSEGV class of bug from earlier versions.

### 2. Bundled in the APK, not fetched over the network
New assets: `feature-chat/src/main/assets/lottie/reaction_{id}.json` (same
6 files, copied in). `MessagePagingAdapter#showActionBottomSheetInner` now
loads each quick-reaction slot straight from there via
`RLottieViewWrapper.loadFromAsset("lottie/reaction_" + id + ".json")` —
the exact same bundled-asset pattern `EmptyChatLottieController` already
uses for the empty-chat wave. No `LottieAssetCache`, no
`EmojiPackDownloadWorker`, no first-chat-open download delay, no "was it
cached or did it need a live swap-in" DEBUG dialog anymore — the animation
is simply always there, instantly, offline, on every device. Tapping a
slot still calls `onReact(m, reaction.unicode)` exactly as before, so
what's stored/applied is unaffected — only the picker's own presentation
changed.

`LottieAssetCache`, `EmojiPackDownloadWorker`, and the server's manifest/
gz endpoints are untouched and still power the empty-chat wave animation's
"maybe the server has a nicer default" path. They're simply no longer
consulted by the reaction picker.

### 3. Entrance/tap animation kept
Staggered pop-in when the sheet opens, quick punch-scale on tap — same as
v123 — layered on top of whichever slot renders (now always the real
Lottie animation).

## Files changed
- `server/emoji-assets/{heart,thumb,laugh,wow,sad,angry}.json` — replaced
  placeholder dots with real vector emoji art (also kept up to date here
  for any future direct-download consumer, even though the picker no
  longer depends on it).
- `feature-chat/src/main/assets/lottie/reaction_{heart,thumb,laugh,wow,sad,angry}.json`
  — new bundled copies of the same art, this is what the picker actually
  loads.
- `MessagePagingAdapter.showActionBottomSheetInner()` — loads
  `RLottieViewWrapper` from the bundled asset per slot; unicode glyph is
  now only a last-resort safety net that should never actually trigger.

## Note on the server zip
If you deploy `callx-server`, redeploy it with the corrected
`emoji-assets/*.json` too — not required for the app fix (the app no
longer needs the server for these), but keeps the two in sync in case
anything else on the server side reads that manifest later. The gzip
cache (`emoji-assets-gz/`) rebuilds itself automatically on next request
since it checks source-file mtime, so no manual cache-busting needed
there.
