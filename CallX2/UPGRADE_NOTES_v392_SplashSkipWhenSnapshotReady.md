# v392 — Splash screen was holding cold start back even when data was ready

## Root cause

MainActivity's splash `setKeepOnScreenCondition` (v240) holds the branded
splash icon on screen until `AppDatabase.isDbWarmupComplete()` returns true
(capped at `SPLASH_MAX_HOLD_MS` = 1200ms). That gate was written to solve
"the splash vanishes instantly and the user watches an empty Chat List
while the encrypted Room DB opens" — true at the time.

But `ChatSnapshotCache` (v210) already solves the exact same problem a
different, lighter way: it renders a real chat list **instantly** from a
plain (unencrypted) SharedPreferences snapshot, completely independent of
whether Room/SQLCipher is warm yet. For any returning user (the common
case — anyone who's opened the app before), that snapshot already exists.
So by the time v210 landed, the v240 splash-hold gate became **redundant**
for that case: it kept blocking cold start on DB warmup for up to 1200ms
even though ChatsFragment could already paint something real the moment
the splash got out of the way. That leftover wait is what still made cold
start feel slower than WhatsApp even after the v390 (ViewModel cache) and
v391 (permission-redirect) fixes.

## Fix

Added `ChatSnapshotCache.hasSnapshot(Context)` — a cheap existence check
(just the underlying SharedPreferences lookup, no JSON parsing) suitable
for a per-frame poll. The splash condition now short-circuits: if a
snapshot exists, dismiss immediately and let ChatsFragment's own
instant-snapshot path (already wired) take over the very next frame. The
DB-warmup wait is now reserved for the one case it's still genuinely
needed — a real first-ever open, where there's nothing else to paint yet.

## Not yet verified on a real device

Static-analysis fix — no Android SDK/emulator available here. Before
trusting fully: confirm on-device that a returning user's cold start now
skips the splash hold almost entirely (near-instant chat list), and that a
genuinely fresh install/first-ever login still behaves exactly as before
(splash hold up to the 1200ms cap, then falls through).
