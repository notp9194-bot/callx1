# v471 - Reel comments: server-side reply paging, likes split out of comment nodes, atomic like write

## DEPLOY RULES FIRST
Merge `firebase_reel_comments_rules.json` into your live rules (new `userCommentLikes` node + `creatorLiked`
on comments/replies). Without it the new like write is rejected and the heart rolls back.
Optional: `tools/migrate_comment_likes.js` (dry-run by default) moves old `likedBy` maps to the new layout.

## #2 Replies paged on the server
`loadRepliesInto()` = `orderByKey().limitToFirst(n+1)` (n = 3, or what the user already opened). "View N more" =
`startAfter(cursor).limitToFirst(9)`, rows appended without rebuilding the thread. A reply you post is shown right
above "View more" (no download of the whole thread) and kept across refreshes.

## #3 likedBy out of the comment node
- Comment/reply nodes keep only `likesCount` + `creatorLiked`. "Did I like it" = `userCommentLikes/{me}/{reelId}/{id}`
  (ONE read per sheet open for all comments + replies of that reel).
- `ReelComment`/`ReelReply`: new `likedByMe` (transient) + `creatorLiked`; legacy `likedBy` is folded in at parse time and nulled.
- DiffUtil: no more `mapSignature` strings. Compares count, likedByMe, creatorLiked; reactions via `Map.equals`.
- Disk cache: `likedByJson` column reused for the 2 flags (no Room migration).

## #4 One write per like
`CommentLikeWriter`: single `updateChildren` = `ServerValue.increment` + my-like marker + `creatorLiked` (+ clears legacy likedBy on unlike).
Notifications: unique work per (liker, comment), cancelled on unlike, and never re-sent once delivered (7-day marker).
Old unique-work name had no liker in it, so a 2nd person's like could be dropped.

Not changed: `reactions` map still inside the comment node. Server count is no longer echoed back after a like (live listener updates comments; replies keep the optimistic count until next load).
Files: ReelCommentFragment, ReelCommentsAdapter, ReelCommentCacheManager, CommentLikeWriter (new), ReelCommentNotifWorker, ReelComment, ReelReply, rules JSON, version.properties (129).
