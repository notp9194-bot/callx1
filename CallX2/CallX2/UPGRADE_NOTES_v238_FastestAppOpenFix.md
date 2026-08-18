# v238 — "Fastest open" fix (cold start / recent-apps-kill open)

## Problem reported
Even after v210 (ChatSnapshotCache) and v237 (Chat List load-time fix),
the app's actual cold open — fresh install/first launch, or reopening
after being swiped away from Recents — was still slow, not fast like
WhatsApp. Those two earlier fixes only sped up **ChatsFragment itself**.
This pass fixes everything **upstream of ChatsFragment** — the part of
the cold-start path the user was actually seeing as "slow."

## Root causes found

### 1. Double Activity hop through a wasted login-screen inflate (the big one)
`AuthActivity` was the app's `LAUNCHER` activity. On **every** cold start
— including the ~99% of opens where the user is already logged in — the
sequence was:

1. OS creates `AuthActivity`'s window
2. `ActivityAuthBinding.inflate()` — the **entire login form** (email/
   password fields, Google button, biometric button, password-strength
   watcher, Google Sign-In client setup, activity-result launchers) gets
   inflated and laid out
3. **Only then** does it check `auth.getCurrentUser()`
4. Starts a brand-new `MainActivity` (second window, second full inflate
   of the toolbar/ViewPager/bottom-nav/FAB layout)
5. Finishes `AuthActivity`

That's two full Activity creations and one completely wasted layout
inflate+layout+draw pass, on top of whatever DB/Firebase warm-up was
already racing in the background — on every single open.

**Fix:** `MainActivity` is now the app's single `LAUNCHER` activity
(WhatsApp/Instagram-style — the home screen *is* the app; login is a
detour, not the front door). It checks `FirebaseAuth.getCurrentUser()`
**before** inflating `ActivityMainBinding`, and only redirects to
`AuthActivity` in the genuinely-logged-out case (fresh install, after
logout). `AuthActivity` itself also now checks-before-inflating as a
defensive measure, though it should rarely even be reached anymore.
All existing explicit `startActivity(..., AuthActivity.class)` call
sites (logout, deep links, crash recovery, etc.) are untouched and keep
working exactly as before — only the manifest `LAUNCHER` category moved.

### 2. No system splash screen — blank/white frame during process init
There was no `SplashScreen` API usage, so between process start and
`MainActivity`'s first real frame, the user could see a blank/white
window flash. Added `androidx.core:core-splashscreen` with a
`Theme.CallX.Splash` theme (light + dark variants) on `MainActivity`.
The OS now paints the launcher icon on a themed background from the
moment the process starts — before `Application.onCreate()`, before any
Activity code runs — and `SplashScreen.installSplashScreen(this)` keeps
it up until the real first frame is ready, so there's never a blank gap.

### 3. ~55+ notification-channel Binder calls blocking the main thread
`CallxApp.onCreate()` was synchronously calling `createChannels()` (~15
channels), `ReelNotificationChannelManager.ensureChannels()` (39
channels), and `YouTubeNotificationChannelManager.ensureChannels()` — all
on the **main thread**, every cold start. Each `createNotificationChannel()`
call is a real Binder IPC round-trip to `system_server`, not free local
work. None of this needs to be ready before the UI paints — a channel
only needs to exist before the *first notification* is shown through it,
which is always well after the user is already looking at the Chat List.

**Fix:** moved all three calls to the existing `app-init-bg` background
thread, first thing on that thread, off the critical path to first paint.

## Files changed
- `app/build.gradle` — added `androidx.core:core-splashscreen:1.0.1`
- `app/src/main/res/values/themes.xml` — added `Theme.CallX.Splash`
- `app/src/main/res/values-night/themes.xml` — dark-mode splash variant
- `app/src/main/AndroidManifest.xml` — `LAUNCHER` moved from
  `AuthActivity` to `MainActivity`; `MainActivity` given
  `android:theme="@style/Theme.CallX.Splash"`
- `app/src/main/java/com/callx/app/activities/MainActivity.java` —
  `SplashScreen.installSplashScreen(this)` added; auth check moved
  before `ActivityMainBinding.inflate()`
- `app/src/main/java/com/callx/app/activities/AuthActivity.java` —
  auth check moved before `ActivityAuthBinding.inflate()`
- `app/src/main/java/com/callx/app/CallxApp.java` — notification-channel
  registration moved from main thread to the `app-init-bg` background
  thread

## Scope note
`ChatSnapshotCache` / `ChatsFragment` / `UiCriticalReadExecutor` (v210,
v237) are unchanged and still correct — they were never the bottleneck
the user was actually seeing; they just never got a chance to run fast
because of the double Activity hop above. With this fix, the real
cold-start path is now: process start → **splash paints instantly** →
`MainActivity` (single inflate) → `ChatsFragment` → **snapshot cache
renders instantly** → real Room/Firebase data replaces it seamlessly —
the same shape as WhatsApp's own cold-start path.
