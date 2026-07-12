# v115 — Status tab visually matches the WhatsApp "Updates" screenshot

## What changed

The Status feed already had a full working feature set (add/view status, seen
tracking, mute, close friends, archive, highlights, reactions, replies,
privacy, search) — it just didn't *look* like the reference screenshot: it
rendered large 116×182dp photo cards instead of the classic small circular
avatars WhatsApp uses.

This update only touches the **visual presentation** of the status row; all
existing feature wiring (Firebase live updates, Room offline cache, seen
tracking, mute, close friends, highlights, search, archive) is untouched and
still fully working.

### Files changed
- `feature-status/src/main/res/layout/item_status_card.xml` — rebuilt as a
  classic circular tile: 64dp ring + circular thumbnail, name truncated to
  one line underneath (was a large rectangular photo card).
- `feature-status/src/main/res/drawable/circle_status_ring_unseen.xml` (new) —
  bright green ring (`#25D366`) for contacts with an unseen update.
- `feature-status/src/main/res/drawable/circle_status_ring_seen.xml` (new) —
  grey ring (`#8696A0`) once every update from that contact has been viewed.
- `feature-status/src/main/res/drawable/bg_status_card_add_badge.xml` —
  changed to the classic green "+" badge (was the gold premium accent).
- `feature-status/src/main/java/com/callx/app/feed/StatusListAdapter.java` —
  `StatusCardAdapter` now binds a single circular thumbnail (status media,
  falling back to the owner's profile photo) instead of a separate
  background photo + small avatar overlay; the "Add status" tile now shows
  the signed-in user's own profile photo (via `setMyPhotoUrl`) and reads
  "Add status" instead of "My Status" until they post one.
- `feature-status/src/main/java/com/callx/app/feed/StatusFragment.java` —
  fetches `users/{uid}/photoUrl` once on start and pushes it into the
  adapter so the "Add status" tile has a real avatar.

## v116 — added the "Channels" section (below Status), matching the screenshot fully

The screenshot's Updates tab has two sections: **Status** (circles row) and
**Channels** (a list of broadcast channels to follow). This project had no
Channels feature at all, so it's added here as a new, self-contained,
fully-working local feature (no backend yet — same tradeoff called out below):

- **Section header** — "Channels" + an "Explore" pill button.
- **Followed channels** — rendered as update rows (icon, name + verified
  badge, latest post preview, time, green unread-count badge) — exactly like
  "Who Cares?" in the reference screenshot (ships pre-followed with a
  999+ badge).
- **"Find channels to follow"** — a collapsible label (tap to expand/collapse,
  state persists) revealing suggested channels, each with a name, verified
  badge, follower count, a **Follow** button and an **X** to dismiss.
- **Explore** button opens a bottom sheet listing every channel with a
  Follow/Following toggle.
- Tapping any channel opens `ChannelViewActivity` — shows its sample posts,
  a Follow toggle, and marks it read (clears the unread badge).

Channel data (`ChannelsRepository`) is a small curated seed list (BBC News,
GNA University, Who Cares?, etc.) with follow/dismiss/unread state persisted
in SharedPreferences — there's no channels backend/CMS, so posts are static
sample text. Follow/unfollow/dismiss/read state all work and persist across
app restarts; wiring a real backend later just means replacing
`ChannelsRepository`'s seed data with a live source — the UI layer doesn't
need to change.

### New files
- `feature-status/src/main/java/com/callx/app/channels/` — `ChannelItem`,
  `ChannelsRepository`, `ChannelsUi`, `ChannelsExploreAdapter`,
  `ChannelsExploreBottomSheet`, `ChannelPostsAdapter`, `ChannelViewActivity`.
- New layouts/drawables for the Channels section and channel viewer.
- `StatusListAdapter` — 4 new row types (channels header, find-label,
  followed row, suggested row) appended after the existing Status/Muted
  sections; `StatusFragment` wires follow/dismiss/explore/toggle actions.
- `ChannelViewActivity` registered in
  `feature-status/src/main/AndroidManifest.xml`.

## Build

Open the project in Android Studio and build/run as usual — no new
dependencies were added.
