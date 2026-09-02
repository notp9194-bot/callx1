# v312 — Reply overlay made public to every viewer

## What changed
v311's bottom-left overlay was owner-only (same privacy boundary as the
"seen by" count). Per follow-up request, it's now a public comment strip
visible to every viewer of the story, not just the owner — matches
Instagram's public post/reel comments rather than a private-reply
indicator.

- `StatusViewerActivity.updateLastReplyOverlay()`: dropped the
  `myUid.equals(ownerUid)` gate. The listener on
  `status/{ownerUid}/{statusId}/replies` now attaches regardless of who's
  viewing, so any viewer (owner or not) sees the same stacked-avatars +
  latest-comment overlay, with the same v311 auto-hide-after-4s behavior.
- No write-path change — replies were already public-readable data
  (`status/{ownerUid}/{statusId}/replies`); only the viewer-side gate
  was owner-only.
- Tap-through is unchanged: any viewer tapping the bubble opens
  `StatusRepliesBottomSheet` and can jump into a DM with a commenter,
  same as the owner could before.

## Files touched
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
- `feature-status/src/main/res/layout/activity_status_viewer.xml` (comment only)
