# v456 — Reel comments: reply thread line + curved arrow (↳)

## What changed
- New `ReplyConnectorView` (feature-reels): 2dp subtle trunk + rounded corner
  + small arrow head into the reply avatar. Trunk continues for non-last
  replies, stops at the curve for the last one. One Path/Paint, no blur/glass.
- `item_reel_reply.xml`: connector added as first child (28dp wide); old 4dp
  start padding removed. Reply card stays smaller than main comment
  (26dp avatar, 12–12.5sp text).
- `item_reel_comment.xml`: `container_replies` moved from the body column to
  the root so the trunk starts under the parent avatar centre
  (marginStart 29dp). Reply indent from parent avatar ≈ 44dp.
- `ReelCommentFragment`: `updateReplyConnectors()` after every add;
  expand = 160ms slide+fade in (only on fresh expand, not on refresh);
  collapse = 160ms fade+slide out, then GONE + removeAllViews.
- `ReelCommentsAdapter`: bind cancels/reset container animation so recycled
  rows never carry a half-finished animation.

## Not changed (on purpose)
- Data model stays 1-level (`reelCommentReplies/{reelId}/{commentId}`).
  Reply-to-reply is shown via `@mention` in the same thread, no 3rd level.

## Files
- feature-reels/.../comments/ReplyConnectorView.java (new)
- feature-reels/.../comments/ReelCommentFragment.java
- feature-reels/.../comments/ReelCommentsAdapter.java
- feature-reels/src/main/res/layout/item_reel_reply.xml
- feature-reels/src/main/res/layout/item_reel_comment.xml

## Heart size bump
- Comment like: icon 10dp → 16dp (padding 17→14dp, tap area 44dp), count 10→11sp.
- Reply like: icon 8dp → 14dp (padding 16→13dp, tap area 40dp), count 9→10sp.
