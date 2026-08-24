# v238 — Splash-gated DB warm-up (perceived cold-start fix)

## Important finding first
`AppDatabase.getInstance()`'s comments (and CallxApp's own log line) claim
the DB is opened via SQLCipher (`net.zetetic:android-database-sqlcipher`
IS a real dependency, and `EncryptedDbKeyStore` generates a real key for
it) — but `Room.databaseBuilder(...)` is never actually given
`.openHelperFactory(new SupportFactory(keyBytes))`. The DB is currently
opened as **plain, unencrypted Room/SQLite**. The SQLCipher library and
key-store code exist but are dead weight right now.

This wasn't touched in this pass — wiring it on is a security decision
(and a real migration: existing installs' plaintext DB would need a
one-time encrypt-in-place step, not just a config flip) that needs an
explicit "yes, encrypt it" before touching. Flagging it here so it
doesn't stay silently assumed-encrypted.

## Root cause of "3 sec blank Chat List on first open"
`SplashScreen.installSplashScreen(this)`'s default dismiss condition is
"this Activity's first frame is drawn" — that fires as soon as
`ChatsFragment`'s (empty/loading) layout inflates, which happens well
before `AppDatabase`'s real file-open cost (paid on the `db-warmup`
background thread, v237) finishes. So the splash disappeared almost
instantly and the user watched an empty/loading Chat List for the real
wait instead of the splash icon — same total time, but reads as "app is
slow" instead of "app is still launching".

## Fix
- **`AppDatabase.java`**: added `isDbWarmupComplete()` /
  `markDbWarmupComplete()`. The existing `isWarm()` only reflects
  `Room.databaseBuilder().build()` having run (cheap, instant) — not
  useful as a gate. The new flag is only set true after a real DAO call
  actually completes.
- **`CallxApp.java`**: the `db-warmup` thread now sets that flag in a
  `finally` block (so it flips even if warm-up throws — a failed
  warm-up shouldn't hold the splash forever, it just falls back to the
  pre-fix behavior of paying the cost on first real screen use).
- **`MainActivity.java`**: `installSplashScreen(this)` now chains
  `.setKeepOnScreenCondition(...)`, holding the splash icon until either
  (a) `AppDatabase.isDbWarmupComplete()` is true, or (b) 1200ms
  (`SPLASH_MAX_HOLD_MS`) have passed since Activity creation — whichever
  comes first. Past the cap, behavior is identical to before this fix.

## Expected effect
On a cold start where DB warm-up finishes within ~1.2s, the user now
sees the OS splash icon for that stretch and then the Chat List appears
already populated — instead of an empty/loading list sitting on screen
for the same duration. Total wall-clock time is unchanged; perceived
speed should be much closer to Telegram's cold-open feel. On a
genuinely slow device where warm-up exceeds 1.2s, the cap kicks in and
the remaining wait shows up on the Chat List same as before (no worse
than pre-fix, never an indefinitely stuck splash).

## Not done in this pass (flagged, needs your call)
- Wiring SQLCipher on for real (security decision + migration step)
- Migration-chain squashing (19 migrations → fewer) — real perf/APK-size
  win but touches every existing install's upgrade path; higher risk,
  needs its own careful pass with test coverage, not a quick edit
