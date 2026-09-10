# v401 — Cold Start: Defer Non-Critical Init + Real Startup Profile

## 1. WorkManager schedule() + privacy sync moved off main thread
`CallxApp.onCreate()` had 6 `WorkManager` `.schedule()`/`.scheduleIfNeeded()`/
`.schedulePeriodicWork()` calls plus a privacy-settings Firebase sync
running synchronously on the main thread before the first frame. The old
comment called these "one-line enqueue, not heavy" — true per call, but
`WorkManager.getInstance(context)`'s FIRST call in the process lazily
builds WorkManager's own `Configuration` and opens **its own Room
database** (`WorkDatabase`) right there. With 6 calls stacked in front of
first paint, that one-time DB-open tax sat directly on the cold-start
critical path for no benefit — none of these workers need to be
scheduled before the Chat List paints, only before their own next
periodic/one-off run window.

Moved into the existing `app-init-bg` background thread (same thread
that already does Firebase persistence config, cache system init, etc.),
right before notification-channel registration. Unchanged otherwise —
still wrapped in try/catch, still fire-and-forget.

Left on main thread (must stay — lifecycle callbacks have to register
before any Activity can start): `PresenceManager.init()`,
`registerForegroundTracking()`, and the domain-verification check
(already documented as "async internally, safe on main thread").

## 2. Real Startup Profile (not just Baseline Profile)
`app/build.gradle` already had `dexLayoutOptimization = true`, but it had
nothing to act on: `dexLayoutOptimization` consumes
`app/src/main/startup-prof.txt` — a separate, narrower profile of only
the methods touched before first-frame — and none of the generator's
journeys in `CallXBaselineProfileGenerator.kt` ever passed
`includeInStartupProfile = true`. Only `baseline-prof.txt` was ever being
produced.

Fixed by adding `includeInStartupProfile = true` to
`generateChatListStartup()` only — the one journey that's genuinely cold
start → Chat List. Every other journey (chat scroll, group chat, swipe
reply, etc.) stays `false` on purpose, so `startup-prof.txt` stays narrow
instead of accumulating every screen's methods.

**Action needed to actually produce the file:** run
`./gradlew :macrobenchmark:generateBaselineProfile` on a connected
device/emulator (same as regenerating `baseline-prof.txt` — see the
generator class doc's "RE-RUN REQUIRED" note). This will write BOTH
`app/src/main/baseline-prof.txt` (updated) and
`app/src/main/startup-prof.txt` (new) into source. Can't be produced
from a static code edit — it's a measured, on-device profile.
