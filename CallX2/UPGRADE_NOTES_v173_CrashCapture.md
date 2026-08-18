# v173 — On-Device Crash Capture (no adb/logcat needed)

Since the build is done from mobile (no Android Studio/adb access), this
adds a global crash catcher so any future crash can be diagnosed without
logcat.

## What's new

1. **`CrashReportActivity`** (new, `app/.../activities/`) — a plain
   code-built screen (no XML layout, so it can't itself fail to inflate).
   Shows the full stack trace in a selectable, scrollable, monospace
   TextView, with:
   - **Copy to Clipboard** button
   - **Restart App** button (relaunches `AuthActivity` cleanly)

2. **`CallxApp.onCreate()`** — registers a global
   `Thread.setDefaultUncaughtExceptionHandler` as the very first line.
   On any uncaught crash anywhere in the app:
   - Full stack trace + thread name + timestamp is written to
     `files/last_crash.txt` (app-private storage, no permission needed)
   - `CrashReportActivity` launches immediately showing that trace
   - Process is then killed cleanly (same as default crash behavior,
     just with the trace captured first)

3. Manifest: registered `CrashReportActivity`
   (`exported="false"`, `singleInstance`).

## How to use it

Next time **any** screen crashes (Reels tab, Home feed, chat, anything),
instead of the app just closing, a red "App Crashed" screen will open
with the full trace. Tap **Copy to Clipboard**, then paste it back —
that's the exact info needed to pinpoint and fix the crash precisely
instead of guessing.
