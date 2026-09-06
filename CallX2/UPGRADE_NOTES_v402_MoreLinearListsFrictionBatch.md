# v402 — Batch reuse: 5 more linear list screens

## What changed
`RecyclerViewFrictionTuner.applyReducedFriction(rv)` added right after
`setLayoutManager()` in:

- `GlobalSavedMessagesActivity.java`
- `CommunityMembersFragment.java` (`rvMembers`)
- `CommunityModerationLogActivity.java` (`rvLog`)
- `CallsFragment.java` (main `rv_calls` list; the nested per-contact call
  history bottom-sheet RecyclerView was left untouched)
- `StatusFragment.java` (`rv_status`) — confirmed no autoplay/video preview
  logic on this list (plain WhatsApp-style row list of contacts' statuses),
  so no timing risk like Home feed's autoplay policy

## Why these are safe
All five are plain single-column vertical row lists — saved messages,
community members, moderation log entries, call history, status rows.
Same shape as chat/comments, no grid or velocity-sensitive
pagination/preloader/autoplay logic involved.

## Safety
Same contract as before: friction field found by type, reflection failure
silently falls back to stock friction, never crashes. No new module
dependencies — `feature-chat`, `feature-calls`, and `feature-status` all
already depend on `:core`.
