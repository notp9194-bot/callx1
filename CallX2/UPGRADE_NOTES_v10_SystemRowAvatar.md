# v10 — Feature 8: Join/Leave System Row Avatar

Scope: only "X joined the group" / "X left the group" rows now carry a
small circular avatar of the member the row is about. Every other system
row (rename, icon change, admin promote/demote, add/remove member) is
untouched and keeps rendering exactly as before.

## Done

- **Message.java** — new `eventUid` / `eventPhoto` fields (set only on
  join/leave system rows).
- **MessageEntity.java** — matching Room columns.
- **AppDatabase.java** — bumped to version 73, added `MIGRATION_72_73`
  (`ALTER TABLE messages ADD COLUMN eventUid/eventPhoto`, nullable, no
  backfill needed), registered in `.addMigrations(...)`.
- **GroupChatActivity.java** — `buildModelUncached()` / `modelToEntity()`
  carry the two new fields through Firebase → Room → UI.
- **MessagePagingAdapter.java** —
  - `viewTypeOf()`: a `"system"` row with a non-empty `eventUid` now
    reuses `TYPE_DATE_SEPARATOR` (the same standalone pill as date
    separators / security-code-change notices).
  - `onBindViewHolder()`: binds the label + resolves `eventPhoto` via
    `ChatAvatarBinder.bindBitmap()`, with a tag-based staleness guard
    (same pattern already used for link-preview thumbs) so a fast
    recycle can't paint the wrong avatar onto a reused holder.
  - `getSelectedMessages()` and `isNonGroupingRow()` updated so these
    rows behave like the other synthetic chip rows (not selectable,
    never visually grouped with a neighbor).
- **DateSeparatorCanvasView.java** — new `setAvatar(Bitmap)`: draws a
  16dp circular avatar (cached `BitmapShader`, same center-crop technique
  `MessageBubbleCanvasView` already uses for the group-sender avatar) to
  the left of the chip text, widening/re-centering the chip. `null` (the
  default) draws exactly as before — plain date/security-event chips are
  unaffected.
- **JoinRequestsBottomSheet.java** — `approve()` now passes the
  approved member's `uid`; a new overload of `postSystemMessage()` does a
  single-value read of `users/{uid}/photoUrl` (falling back to
  `thumbUrl`) before posting, so "X joined the group" gets their avatar.
- **GroupInfoActivity.java** — new `postSystemMessage(text, eventUid,
  eventPhoto)` overload; the self-leave flow (`doLeaveGroup()`) now calls
  it with `currentUid` + `FirebaseUtils.getCurrentPhotoUrl()` (both
  already available synchronously, no extra read needed). Added the
  missing `androidx.annotation.Nullable` import this needed.

## Not done yet

Nothing — the notification-based "Leave group" quick action
(`NotificationActionReceiver.java`, `ACTION_GROUP_LEAVE`) now also sets
`eventUid`/`eventPhoto` (via `myUid` + `FirebaseUtils.getCurrentPhotoUrl()`,
both already available at that call site) inline in the same `sys` map it
was already building, so every join/leave path — admin-approved join,
in-app leave, and notification-action leave — shows the avatar.
