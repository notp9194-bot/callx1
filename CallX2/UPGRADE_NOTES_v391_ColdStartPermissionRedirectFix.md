# v391 — Cold start "slow open" root cause: permission Settings redirects

## Root cause

`MainActivity.onCreate()` called `requestPermissions()` unconditionally on
**every** cold start, before the ViewPager/Chats tab was even set up. Two
of its three checks don't show a lightweight permission dialog — they call
`startActivity()` straight into a **system Settings screen**:

- `ACTION_MANAGE_OVERLAY_PERMISSION` if `canDrawOverlays()` is false
- `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (Android 14+) if
  `canUseFullScreenIntent()` is false

Neither permission is required for the Chats tab. Overlay is only for the
small-window/chat-heads feature (already requested contextually elsewhere:
`PrivacyDirectDialog`, `SmallWindowManager`, `ChatActivity`,
`NotificationActionReceiver`). Full-screen-intent only matters for the
incoming-call UI on Android 14+. Most users never grant either optional
permission, so these checks stayed `false` **forever** — meaning every
single cold start (first-ever open, or the OS having killed the app) sent
the user straight to a system Settings screen instead of ever letting the
Chats tab draw. That app → Settings → back detour, repeating on every
relaunch, is what read as "screen bahut slow open hoti hai" — the Chats
tab itself was never actually the bottleneck; it never got the chance to
be, because MainActivity had already navigated away from itself before the
ViewPager was even attached.

## Fix

Added `OptionalPermissionPrefs` (same one-time-prompt idiom already used
by `ReelDisplayModePrefs.hasBeenAsked()`):

- Each of the two Settings-redirect checks now fires **at most once ever**
  per install. A user who ignores/declines it isn't sent back on their
  next relaunch.
- `requestPermissions()` split into `requestNotificationPermission()`
  (kept eager — a real permission dialog, not a screen navigation, so it's
  cheap) and `requestOptionalSettingsPermissionsOnce()` (the two Settings
  redirects).
- The Settings-redirect half is now invoked via
  `binding.getRoot().post(this::requestOptionalSettingsPermissionsOnce)`
  from `onCreate()` — i.e. after the real first frame is already queued to
  draw, not before it. Even on the one time it does fire (first ever
  check), it can never again stand between a cold start and the user's
  first real view of the chat list.

## Not yet verified on a real device

Static-analysis fix — no Android SDK/emulator available here to build and
profile. Before trusting fully: confirm on a fresh install that the Chats
tab now draws immediately on cold start with no Settings-screen detour,
and that the one-time overlay/full-screen-intent prompts still appear
exactly once (not zero times) so the small-window and incoming-call
features aren't silently broken by never asking at all.
