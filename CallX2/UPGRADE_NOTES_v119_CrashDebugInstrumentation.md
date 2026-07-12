# v119 — Debug instrumentation: full crash report on-screen (mobile-build friendly)

Since you're building from mobile without easy logcat/adb access, this
pass adds two things so the NEXT crash tells us exactly what's happening,
directly on-screen, no computer needed:

## 1. Immediate AlertDialog for plain Java exceptions
`showActionBottomSheet` now wraps its entire body (`showActionBottomSheetInner`)
in try/catch. Any regular Java exception/error while building the
reaction picker or action sheet shows a full stack trace in an
AlertDialog **right then, same session** — no restart needed.

## 2. Native-crash breadcrumb (for the SIGSEGV case)
A native crash (what we suspect is still happening) kills the whole
process instantly — no Java code runs afterward, so nothing can show a
dialog "at the moment." Instead: `CrashDebugHelper.markLottieLoadStarting()`
writes a synchronous (commit(), not apply()) breadcrumb to disk
**immediately before** every `RLottieViewWrapper.loadFromFile()` call —
which reaction id, which file, size, thread, timestamp — and
`clearLottieLoadMarker()` erases it immediately after a successful
return. Wired into both the cached-branch and live-swap-branch loadFromFile
call sites in `MessagePagingAdapter`.

**On next app launch**, `CallxApp.onCreate()` checks for a leftover
breadcrumb (meaning the app died mid-call last time) and shows it in the
same "Debug error" AlertDialog that already existed for `AXrLottie.init()`
failures. If it's the native crash, you'll see exactly which reaction id
+ file was being loaded when it died — that pinpoints whether it's
always the same one (probably a specific still-bad file) or all of them
(probably something structural in the JNI bridge itself, not the JSON).

## What to do
1. Install this build.
2. Long-press until it crashes again (as before).
3. Reopen the app — a "Debug error" AlertDialog should appear automatically
   on the very first screen with the breadcrumb detail (or the Java stack
   trace if it turns out not to be native after all).
4. Screenshot/copy that dialog's full text back.

This is DEBUG-build only (`BuildConfig.DEBUG` gated) — none of this runs
in release builds.
