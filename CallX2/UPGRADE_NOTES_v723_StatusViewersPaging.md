# v723 — Status "Seen by" sheet: Instagram-style paging

- seenBy: newest 40 only (`orderByValue().limitToLast(40)` child listener = first page + live deltas); older pages on scroll (`endAt(ts,key)`, 41 rows, inclusive cursor). Window-slide "removed" events ignored.
- reactions: child listener (deltas, no full snapshot rebuild). replies: newest 300 (indexed on timestamp).
- Total count: server counter `status/{owner}/{id}/viewCount` (StatusSeenTracker.countFirstView, once per viewer). Carousel listens to that single number, not the whole seenBy node. Legacy stories fall back to snapshot size.
- Followers: no more bulk read of the owner's whole follower list; checked per loaded viewer only while a follower filter is on (chip counts for the two follower filters are hidden).
- Search / filters: bounded auto-load (max 2000 viewers), 12 parallel profile reads while searching.
- Refresh debounce 60 ms -> 250 ms on busy stories.

## DEPLOY RULES FIRST
Merge `firebase_status_viewers_rules.json` (already merged into firebase_rules_deployed_updated.json):
`seenBy` needs `".indexOn": [".value"]` (otherwise orderByValue downloads everything), and `viewCount` write rule.
Without the rules the app still works (counter write is rejected silently, paging falls back to client-side filtering).

## v724 — rules + counter hardening
- New full rules file: `firebase_security_rules_UPDATED_audio_v4_statusviewers.json` (your audio_v3_statusexpiry + status changes only).
  - `seenBy`: `.indexOn [".value"]` (needed for paging) + blocked viewers cannot write seen / reaction / reply on the owner's story.
  - `viewCount`: +1 only, and only in the SAME atomic write that creates `seenBy/{me}` (so one viewer = one count, no inflating).
  - `viewDurations/{me}`: viewers may write their own duration (was silently rejected before).
- App: `FirebaseUtils.markStatusSeenCounted` — one atomic write {seenBy/me, viewCount+1}; if rejected (already seen / old rules) falls back to plain seenBy. Used by StatusSeenTracker (single + batch) and StatusRepository (which also used client time, which the `<= now` rule could reject -> now server time).

## v725 — sheet polish
- Loading feedback: pulsing skeleton rows until the first page arrives (no more "No viewers" flash), spinner under the list while an older page loads.
- Reactor/replier rows no longer sink to the bottom with no time: reply time is used, otherwise a tiny `seenBy/{uid}` lookup (visible rows only, 4 parallel, does not move the paging cursor).
- "N new viewers" pill (tap = scroll to top) when new viewers arrive while scrolled down; clears when back at top.
- Profiles: persisted to disk (800 entries) and shown instantly even when stale; re-read only for bound rows older than 6 h (was 5 min, memory only). Repeat opens = ~0 profile reads.
