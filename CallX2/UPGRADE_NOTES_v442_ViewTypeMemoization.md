# v442 — Feature 2 ultra-advanced pass: memoized per-bind view-type resolution

Scope: `MessagePagingAdapter#viewTypeOf()` — the single most-called piece of
Feature 2's per-bind cost (per the earlier cost breakdown: `isGroupAvatarRunTail()`
runs on every row bind to decide who draws the run's avatar, and internally asks
"what view type would the NEXT row get").

## The redundant work

The same `Message` instance's view type gets fully recomputed from scratch up
to 3 separate times per scroll pass:

1. Its own row — `getItemViewType(position)` (RecyclerView's own machinery).
2. The row ABOVE it — `isGroupAvatarRunTail()` → `willShowGroupSenderAvatar(nm)`
   → `viewTypeOf(nm)`, needed to know whether the NEXT row will draw a group
   avatar (this is the tail-decision itself, and it runs on every single bind).
3. For the user's OWN sent rows — `canShowSeenBy(m)` → `viewTypeOf(m)`, re-run
   on every "seen by" strip rebind (every scroll re-layout of that row, every
   live readBy tick), not just once.

Each of those calls used to re-walk the full branch chain: half a dozen
`String#equals` checks (`date_separator` / `security_event` / `system`+`eventUid`
/ `status_seen` / `reel_seen` / `call_entry`), a `viewOnce` check, then
`isCanvasEligible()`'s own type-string switch — for a plain text message, every
one of those checks runs and fails before landing on the real answer.

## The fix

`viewTypeOf()` is now a thin memoizing wrapper; the actual chain moved to
`computeViewTypeOf()`, called at most once per `Message` instance:

```java
private int viewTypeOf(@Nullable Message m) {
    if (m == null) return TYPE_RECEIVED;
    if (m.cachedAdapterViewType != 0) return m.cachedAdapterViewType;
    int type = computeViewTypeOf(m);
    m.cachedAdapterViewType = type;
    return type;
}
```

New `Message.cachedAdapterViewType` (core model, `transient int`, default `0`) —
a scratch slot owned by this one adapter method, not a real message field:
`transient` so Firebase's reflection POJO mapper and Room both silently skip it
(no serialization, no equality, no `DiffUtil` involvement). `0` is a safe "not
yet computed" sentinel since every real `TYPE_*` constant is `>= 1`.

**Why memoizing on the object is safe (no cache ever goes stale):**
`computeViewTypeOf()` is a pure function of the instance's own fields plus the
adapter's session-constant `currentUid` — and a `Message` instance's
type-relevant fields never change in place. Every realtime/Room/Firebase update
that could change what a message renders as hands the adapter a **brand-new**
`Message` object instead (see `DIFF.areContentsTheSame`'s own "fresh objects"
note) — which starts with a fresh, uncached `0` slot. Confirmed by grep: the
entire codebase has exactly ONE spot that mutates an already-bound `Message`'s
`type`/`deleted` in place — `applyRealtimeUpdate()` — which now explicitly
resets `current.cachedAdapterViewType = 0` as part of that mutation, so a
message that gets edited/retyped/deleted via that path is never stuck showing
a stale view type.

## Net effect

A group chat's hot per-bind path (`isGroupAvatarRunTail`) drops from "re-walk
a ~7-branch string-comparison chain for the neighbor" to "one int field read"
on every bind after the first time either row's view type was resolved —
which, given `getItemViewType()` and the tail-check both ask about overlapping
rows, is effectively every bind after the very first layout pass. The seen-by
strip's repeated `canShowSeenBy()` calls on a user's own rows see the same win
on every live read-receipt tick, not just once.

## Files
- `core/src/main/java/com/callx/app/models/Message.java` — new
  `cachedAdapterViewType` field
- `feature-chat/.../conversation/MessagePagingAdapter.java` — `viewTypeOf()` /
  `computeViewTypeOf()` split; cache reset added to `applyRealtimeUpdate()`

## Not touched
`isGroupAvatarRunTail()`/`isGroupNameRunHead()` themselves are NOT memoized —
those depend on NEIGHBOR identity (what's above/below), which genuinely
changes on every insert/remove, and the existing payload-based invalidation
for them (`refreshGroupSenderRow`, the group `AdapterDataObserver`,
`onMemberPhotosChanged`) is already deliberately, carefully wired — see v434's
"staleness (the trap)" note. Caching THAT would need matching invalidation at
every one of those call sites; `viewTypeOf()` needed none, since nothing else
in the codebase mutates a bound Message's type in place.

Not compile-tested here (no Android SDK/Gradle in sandbox) — Java parse-checked
only (brace balance confirmed on both touched files); run a normal build.
