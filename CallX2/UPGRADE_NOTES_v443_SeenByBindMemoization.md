# v443 — Feature 4 (Seen-by avatars) per-bind cost: memoized strip result

Scope: `MessagePagingAdapter#bindSeenByAvatars()` — runs on every own sent-row bind in a group (scroll re-bind + every readBy tick).

## Redundant work per bind (before)
For any own row with readers, EVERY bind redid: `selectSeenByReaders()` (up to 80 `peek()` calls scanning for the next own row,
two passes over `readBy`, `String[]`/`long[]` allocs) + `StringBuilder` key build + N `groupMemberPhotos.get()` + N `url.hashCode()`.
Result was identical on every scroll pass unless something structural changed.

## Fix
The strip is a pure function of (this row's readBy, next own row's readBy, groupMemberPhotos). Result is now frozen per `Message`
instance (`SeenByBound`: key, urls, shown, overflow) and reused while the adapter's `seenByEpoch` is unchanged.
Scroll re-bind = one int compare + `cv.setSeenBy(key…)` + the (unavoidable, L2/L3-cached) `ChatAvatarBinder.bindBitmap()` calls.
Zero alloc, zero peek() scan on cache hit.

Epoch (`bumpSeenByEpoch()`, process-wide sequence so a cached Message can't match a new adapter) is bumped on:
- any adapter insert / remove / move / change (group `AdapterDataObserver`; added `onItemRangeChanged` overloads)
- `applyRealtimeUpdate()` (readBy replaced)
- `notifyReadByChanged()`
- `setGroupMemberPhotos()` / `onMemberPhotosChanged()`
- new public `invalidateSeenByCache()` — called from `GroupChatActivity` where `m.readBy` is replaced outside the adapter (Message-Info fresh read)

"Nothing to show" is cached too (`SEEN_BY_NONE`), so own rows with no readers skip the scan as well.
Fresh `Message` objects (DiffUtil/Room/Firebase) start uncached (`cachedSeenByEpoch = 0`) — same safety argument as v442.

## Files
- `core/.../models/Message.java` — `transient cachedSeenByEpoch`, `transient cachedSeenBy` (Firebase/Room skip)
- `feature-chat/.../conversation/MessagePagingAdapter.java` — memoized `bindSeenByAvatars()`, `computeSeenByBound()`, epoch plumbing
- `feature-chat/.../group/GroupChatActivity.java` — `invalidateSeenByCache()` call

Not compile-tested here (no Android SDK/Gradle) — brace balance checked only; run a normal build.
