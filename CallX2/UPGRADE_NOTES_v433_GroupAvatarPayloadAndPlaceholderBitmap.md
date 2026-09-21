# v433 — Group sender avatar: payload refresh + cached placeholder bitmap

Scope: received-message 20dp group-sender avatar (canvas bubbles). Items 1 & 2 of
the group-avatar optimisation list only (items 3-6 intentionally NOT touched).

## 1. Rebind churn → payload-only avatar swap

**Problem.** `memberPhotos` is filled asynchronously (presence snapshot /
`users/{uid}` single read). Photos that arrived *after* a row was bound never
reached the row — `MessagePagingAdapter.setMemberPhotos()` is a stored-only
no-op and the presence listener never told the adapter anything — so the row
stayed on the placeholder circle until the user scrolled it out and back.

**Why not DiffUtil directly.** `DIFF` compares `Message` objects; a member photo
is not a `Message` field, so DiffUtil can never observe it. The equivalent
mechanism is the same one DiffUtil uses internally: `notifyItemChanged(pos, payload)`.

**Fix.**
- `MessagePagingAdapter`
  - new `PAYLOAD_MEMBER_AVATAR`
  - new `onMemberPhotosChanged(Collection<String>)` / `onMemberPhotoChanged(String)`:
    scans only visible rows ± 16 (max item-view-cache), uses `peek()` (no Paging
    load hints), fires the payload only for rows whose `senderId` is in the changed set
  - `onBindViewHolder(payloads)` routes the payload to new `bindGroupSenderAvatarOnly()`
    → `ChatAvatarBinder.bindBitmap()` (same L2/L3 pipeline) → `setGroupSenderAvatarBitmap()`
    → `invalidate()` only. **No re-measure, no full bind.**
  - does NOT bump `canvasBindToken` (would cancel the row's other in-flight loads);
    snapshots it so a recycle before the async decode finishes still drops the result
  - merged payload lists (e.g. avatar + read-by in one frame) are handled without a full-bind fallthrough
- `MessageBubbleCanvasView`: `isGroupSenderAvatarVisible()` so the fast path skips
  rows with no avatar column (sent / 1:1 / broadcast label)
- `GroupChatActivity`
  - new `putMemberPhoto()` returns true only if the stored URL really changed
    (the presence snapshot re-fires on every member lastSeen tick — without this
    diff every tick would refresh avatars)
  - presence listener + `resolveMemberProfileIfNeeded()` now call the adapter with
    only the uids whose photo actually changed

Rows outside the scanned window are untouched: they haven't been bound yet, so
their first bind reads the already-updated map.

## 2. Placeholder → cached flat-gray circle Bitmap

`drawGroupSenderAvatar()` used `canvas.drawOval(rect, AA paint)` every draw()
while the photo was unresolved. Now a pre-rendered `GROUP_AVATAR_PLACEHOLDER_COLOR`
circle Bitmap is built once per pixel size (`GROUP_AVATAR_PLACEHOLDER_BITMAP_CACHE`,
same pattern as `FORWARD_BTN_BITMAP_CACHE`) and blitted with `drawBitmap()`.
- circular (transparent corners) so it still reads as an avatar, AA edge baked in once
- per-view field memoises the shared bitmap → per-frame path is a field read, no lock
- integer-rounded blit origin keeps the baked edge crisp
- never recycled / never cleared by `clearRecycledBitmaps()`; ~14KB @ 20dp xxhdpi
- removed now-unused per-instance `groupSenderAvatarPlaceholderPaint`

## Files
- feature-chat/.../conversation/MessagePagingAdapter.java
- feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java
- feature-chat/.../group/GroupChatActivity.java

## Not compile-tested here
No Android SDK/Gradle in the sandbox — please run a normal build. Only uses APIs
already present in the project (`PagingDataAdapter.peek()` from paging 3.2.1).
