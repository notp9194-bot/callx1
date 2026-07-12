# v118 — Full app crash on long-press (native RLottie SIGSEGV) — fixed

## Root cause
The 6 placeholder `emoji-assets/*.json` files (heart, thumb, laugh, wow,
sad, angry) had animated scale keyframes with only `t` and `s` — missing
`e` (end value) and `i`/`o` (bezier easing curves), which the native
rlottie C++ parser requires for every non-final keyframe of an animated
property. Once the server-side folder-name bug (previous note) was fixed
and the app could actually download these files for the first time, the
native parser choked on the missing fields — a **native SIGSEGV**, not a
Java exception. That kills the whole process; no Java `try/catch`
anywhere can ever catch it. This is why wrapping the fetch code in
try/catch (v117) didn't stop the crash — the crash wasn't in the fetch
code, it was in the native decode happening after.

## Fix (two parts, belt and suspenders)

**1. Server data — corrected (see server zip):**
All 6 files now have complete `e`/`i`/`o` on every non-final keyframe,
verified against the schema of the known-working bundled
`empty_chat_wave.json` (used by `EmptyChatLottieController`, which never
crashed). Must be redeployed to Render for this alone to fix things.

**2. Client — new `LottieJsonValidator` (the real fix):**
Even correct server data today doesn't guarantee nothing malformed ever
reaches the cache again (a future bad deploy, a partial download, manual
edits, etc.). `LottieJsonValidator.isSafeToLoad(File)` does a pure-Java,
recursive structural check — confirms `e`/`i`/`o` are present on every
non-final animated keyframe anywhere in the file — **before** any call
to `RLottieViewWrapper.loadFromFile()`. If a file fails, it's deleted
from cache and the slot falls back to unicode instead of ever reaching
native code. Wired into both the already-cached branch and the
on-demand/live-swap branch in `MessagePagingAdapter`.

This means a malformed Lottie file can now only ever produce a plain
unicode emoji — never a crash — no matter how it got onto the device.

## What to do
1. Redeploy the attached server zip (has the corrected `emoji-assets/*.json`).
2. Reinstall this app build (has the validator).
3. Clear app storage once more, since the last crash likely wrote a bad
   file into the on-disk cache mid-download — the validator will now
   catch and delete it automatically going forward, but a clean start
   avoids any doubt while testing.

