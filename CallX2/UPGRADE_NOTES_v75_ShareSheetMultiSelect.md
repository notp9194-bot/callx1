# v75 — Reel Share Sheet: Multi-Select "Send to"

Upgrades `ReelShareSheetFragment` (the bottom sheet opened from the reels feed's
share button — `feature-reels/src/main/java/com/callx/app/social/`) to support
multi-select send, matching the IG/WhatsApp-style share sheet: search box,
checkmark badges on selected contacts, a message box, and two send actions.

## What changed

**`item_reel_share_contact_grid.xml`** — added a dark scrim + blue checkmark
badge (new drawable `bg_share_selected_check.xml`) over an avatar when it's
selected. Shares the same bottom-end corner as the existing online-status dot;
the two are mutually exclusive (a selected contact's dot is hidden while checked).

**`fragment_reel_share_sheet.xml`** —
- New search bar above "Send to" (`et_share_search`), filters the grid live.
- New `ll_selection_actions` block (hidden until 1+ contacts are checked):
  a message `EditText` (`et_share_message`) plus two buttons —
  `btn_send_separately` and `btn_send_to_group` (the latter only shown at 2+
  selected).
- The original icon row (Copy Link / Share via / Story / Status / Repost,
  `ll_share_button_row`) is untouched — deliberately, so its existing
  expand/collapse height animation (`animateSheetContent()`) is never
  disturbed by selection state. Both rows can be visible at once.

**`ReelContactShareAdapter.java`** — rewritten:
- Tapping an avatar now toggles a checkmark (`selected: LinkedHashMap<uid,User>`,
  insertion-order preserved) instead of sending immediately.
- `filter(query)` narrows a `displayed` subset of the full `masterContacts`
  list by name, case-insensitive; selection persists across a search.
- Online-status snapshot moved from a position-indexed `boolean[]` to a
  uid-keyed `Set<String>` so it stays correct once `displayed` is a filtered
  subset of `masterContacts` (position no longer maps 1:1 to a stable contact).
- New `OnSelectionChangedListener` replaces the old `OnContactShareListener`
  (which fired an immediate send on tap — removed, no other callers existed).

**`ReelShareSheetFragment.java`** —
- `onSelectionChanged()` shows/hides `ll_selection_actions` and updates the
  two button labels/visibility as the selection count changes.
- `sendReelToContact()` — extracted from the old `onShareToContact()` override;
  now takes an optional custom message (from `et_share_message`) instead of
  always building the default caption+link text.
- `sendSeparately()` — loops every checked contact, pushes an individual
  `reel_share` 1:1 message + FCM push to each (same message shape as before,
  now reusable per-contact), one toast/dismiss/share-count-increment at the end.
- `sendToNewGroupChat()` — only reachable at 2+ selected. Creates a brand-new
  group using the **same Firebase write shape** `feature-chat`'s
  `NewGroupActivity` uses (`groups/{id}` with `members`/`admins`/`unread` maps,
  fanned out to `userGroups/{uid}/{id}`), auto-named from the first 3 selected
  contacts' names, then pushes the reel share as the group's first message via
  `getGroupMessagesRef(groupId)` and notifies members via
  `FirebaseUtils.sendGroupPushNotification()`. This is a **plain HashMap write**
  (no Room caching, no E2EE) — deliberately matching the existing lightweight
  level of `sendReelToContact()`'s 1:1 send, not `GroupChatActivity`'s full
  offline-outbox/encrypted pipeline. `feature-reels` only depends on `core`
  (not `feature-chat`), so this couldn't reuse `GroupChatActivity`/`NewGroupActivity`
  code directly — it's a fresh, minimal write against the same schema instead.

## Not touched
- `ReelShareSheetActivity` — a separate, older share Activity still used by
  `RepostWithCaptionActivity`, `CollabRepostActivity`, and `SoundDetailFragment`'s
  music share flow. Out of scope; the screenshot's multi-select flow was the
  bottom-sheet fragment used by the main reels feed.
- No compiler/Gradle run was possible in the environment this patch was written
  in (no network/build tooling available) — logic and Firebase field names were
  cross-checked against `NewGroupActivity`, `GroupChatActivity`, and
  `FirebaseUtils`, but please run a real `./gradlew assembleDebug` before
  shipping.
