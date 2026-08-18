# v244 — Reel Comment System: Security Hardening

Follow-up to a review of the reel comment system (ReelCommentFragment /
ReelCommentsAdapter / ReelPinnedCommentsActivity). The UI itself was already
feature-complete (sort, search, edit, pin, react, reply, report, mentions,
pagination) — the gaps were all in what happens *outside* the UI.

## 1. No server-side enforcement for comments (the big one)

Every "only the owner can edit/delete", "only the reel owner can pin", and
"only your own uid" rule lived exclusively in Java (`isOwn`, `isReelOwner`
checks in `ReelCommentsAdapter.showContextMenu()` / `togglePin()`). The
actual Firebase Realtime Database rules had no per-node restrictions for
`reelComments`, `reelCommentReplies`, `reelCommentReports`, or
`reels/{id}/commentsCount` — meaning any authenticated client (a modified
APK, or a raw REST call with a valid ID token) could edit or delete
**anyone's** comment, pin arbitrary comments, spam fake reports, or tamper
with the comment counter.

**Fix:** added `firebase_reel_comments_rules.json` — a rules fragment
(same merge-in convention as `firebase_repost_rules.json` etc.) that:
- Restricts comment/reply creation to `uid === auth.uid`.
- Restricts delete to the comment's own author OR the reel's owner.
- Restricts `text`/`isEdited`/`editedAt` edits to the original author only.
- Restricts `isPinned` toggling to the reel's owner only.
- Restricts `likedBy/{uid}` and `reactions/{uid}` writes to your own uid.
- Makes `reelCommentReports` / `reelReplyReports` write-once (create-only,
  under the reporter's own uid) and unreadable by normal clients.
- Validates `commentsCount` can't go negative or non-numeric.

**⚠️ Read the `_instructions` block in that file** — if your deployed rules
have a blanket `"auth != null"` write at the root, it overrides everything
below it (RTDB permission cascades downward). The fragment alone does
nothing until the blanket root write is narrowed.

## 2. Blocked users' comments still visible

Blocking someone elsewhere in the app (`blocks/{myUid}/{blockedUid}`) had no
effect on the reel comment thread — their comments and replies still showed
up. `ReelCommentFragment` now live-listens to your blocklist and filters
both the top-level list (`applyFilterAndSort()`) and reply threads
(`loadRepliesInto()`). This is a UI-level filter only, consistent with how
blocking works elsewhere in the app — the data isn't deleted, just hidden
from the blocker.

## 3. No rate limiting on posting

Nothing stopped mashing the send button — each tap fired a full write with
no gap. Added a 2-second client-side cooldown in `onSendClicked()` as a
first line of defense. This is a UX-level throttle, not a real anti-abuse
measure — a scripted client can still ignore it, which is exactly why the
server-side length/uid validation in `firebase_reel_comments_rules.json`
matters more.
