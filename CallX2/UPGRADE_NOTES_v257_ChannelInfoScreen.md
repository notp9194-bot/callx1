# v257 — Channel info full-screen (Status → Channel → ⋮ → "Channel info")

## What's new
Status tab → open any Channel → toolbar ⋮ overflow menu now has a new
**"Channel info"** item at the top. Tapping it opens a full-screen,
Telegram/WhatsApp-style channel details page:

- Back arrow, avatar, name + blue verified badge, "Channel • N followers"
- Quick-action row: **Following/Follow**, **Forward**, **Share**, **Search**
  (Search closes the screen and expands the channel's toolbar search field)
- Description (auto-linked email/links) + "Created on M/d/yy"
- **Mute notifications** toggle (Material switch)
- **Public channel** / **Private channel** info row (dynamic, from
  `ChannelEntity.isPrivate`)
- **Profile privacy** info row (tap → explainer dialog)
- **Clear media files** (clears Glide disk + memory cache, confirm dialog)
- **Unfollow channel** (red, hidden for owner/admin, confirm dialog)
- **Report channel** (red, hidden for owner/admin, confirm dialog)

All actions call into the existing `ChannelViewModel` — same
follow/unfollow/mute/report Firebase+Room plumbing `ChannelViewerActivity`
already used from its overflow menu, just surfaced as a dedicated screen
instead of one-off menu items.

## Implementation
- **New file**: `feature-status/src/main/kotlin/com/callx/app/channel/ChannelInfoActivity.kt`
  — CallX2's first Kotlin class inside `feature-status` (previously only
  `app` module had one, `AboutActivity.kt`). Entirely code-built UI (no XML
  layout), same pattern as `AboutActivity.kt`.
- `feature-status/build.gradle` — applied `org.jetbrains.kotlin.android`
  plugin + `kotlinOptions { jvmTarget = "17" }` + `kotlin-stdlib` dependency
  so the module compiles Java and Kotlin side by side.
- `feature-status/src/main/AndroidManifest.xml` — registered
  `ChannelInfoActivity` (`Theme.CallX`, not exported).
- `feature-status/.../res/menu/menu_channel_viewer.xml` — added
  `action_channel_info` ("Channel info") item, shown to everyone (admins too).
- `ChannelViewerActivity.java`:
  - `onOptionsItemSelected()` routes `action_channel_info` → new
    `openChannelInfoScreen()`, which `startActivityForResult`s
    `ChannelInfoActivity` with the channel id/name/icon/verified/followers/
    ownerUid extras (falls back to the launch-intent extras before the live
    `ChannelEntity` has loaded).
  - New `RC_CHANNEL_INFO` request code + `searchMenuItem` field (captured in
    `onCreateOptionsMenu`) so `onActivityResult` can `expandActionView()` on
    the toolbar SearchView when `ChannelInfoActivity` finishes with
    `ACTION_OPEN_SEARCH` (from tapping "Search" on the info screen).

## Notes
- Follow state, mute state, and all channel metadata come from
  `viewModel.getChannel(channelId)` LiveData — same Room-cached,
  Firebase-synced `ChannelEntity` `ChannelViewerActivity` observes, so the
  info screen and the channel feed always agree and update live.
- "Forward" and "Share" both currently open Android's native share sheet
  with the channel's invite link (or a generated `callx.app/channel/{id}`
  fallback) — kept intentionally simple/robust rather than wiring a
  in-app contact picker that didn't already exist for "share a channel"
  (only "share a post" does, via `ForwardPostActivity`, which requires a
  real `postId`).
