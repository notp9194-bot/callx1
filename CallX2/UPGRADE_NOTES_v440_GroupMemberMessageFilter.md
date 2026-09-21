# v440 — "Messages from <member>" filter (group chat, avatar tap)

Item 7 of the avatar-system list. Tap a sender's avatar → member sheet → **Messages from X** opens
the existing in-chat search bar filtered to that member.

## Flow
- **Avatar tap** (`onGroupSenderAvatarClick`) now opens a compact bottom sheet instead of going straight to the
  photo viewer: header (avatar, name in the member's bubble color, "Group creator / Group admin" line) +
  rows **View photo** (same `DialogFullscreenHelper.showAvatarZoom` viewer as before) · **Messages from X** ·
  **Mention X** (same `GroupMentionController.insertMention`). Tapping the header avatar also opens the photo.
- **Avatar long-press** is unchanged: inserts the `@mention` directly.
- **Messages from X** → `ChatSearchController.openSearchForSender(uid, name, color)`:
  - search bar slides in (keyboard NOT raised) with a tinted `Name ✕` chip (member's name color, ~18% fill —
    same look as the admin/creator pill). Tap the chip → filter removed, plain all-members search.
  - empty text = every message that member sent; jumps to the NEWEST first, ▲/▼ walk back/forward in time,
    "M of N" counter, "No results" if none. Typing ≥2 chars narrows to that member's messages containing the
    text (1 char = still "all their messages", results don't blank out while typing).
  - the filter never outlives the bar: `closeSearch()` and the ⋮ → "Search Messages" plain open both clear it.
  - opening it again for another member while the bar is open just swaps the filter (typed text is kept).

## Data (`MessageDao`, no schema change / no migration)
- `getMessageIdsBySender(chatId, senderId, limit)` — all of a member's messages.
- `searchMessageIdsFtsBySender(...)` — FTS hit-set intersected with `messages` by id (`messages_fts` has no
  senderId column; adding one would need a migration + trigger rewrite). `@SkipQueryVerification` like the
  existing FTS query.
- `searchMessageIdsLikeBySender(...)` — LIKE fallback, same safety net as the unfiltered search.
- All three return the NEWEST `limit` (500) matches re-sorted ASC, so "lands on most recent" stays true for a
  member with >500 messages (a plain `ORDER BY ASC LIMIT` would have kept the oldest 500).
- Excluded: soft-deleted rows and `date_separator / security_event / status_seen / reel_seen / view_once`.

## Files
- core/.../db/dao/MessageDao.java
- feature-chat/.../conversation/controllers/ChatSearchController.java (member mode, `hasRun`, `applyResults`)
- feature-chat/.../group/GroupChatActivity.java (`showMemberActionSheet`, `ensureSearchController`,
  `openMemberMessageSearch`; the inline SearchDelegate moved into `ensureSearchController`, behavior unchanged)
- feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java (`groupSenderColorForUid` → public static, so the
  sheet + chip use the exact bubble color)
- feature-chat/src/main/res/layout/layout_search_bar.xml (`tv_search_member` chip, gone by default; 1:1 chat never shows it)

## Not compile-tested here (no Android SDK/Gradle in sandbox) — Java syntax-checked with javac only; run a normal build.
