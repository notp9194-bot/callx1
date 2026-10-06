# v457 — Reel comments: reply thread line polish

## Fixes
1. **Line broke between replies** — `item_reel_reply.xml` had 8dp vertical padding;
   padding clips child drawing, so the negative-margin trick never worked and a
   gap stayed between rows. Row padding removed; the 8dp spacing now lives in the
   avatar (marginTop 10dp), body (marginTop/Bottom 8dp) and like column
   (marginTop 1dp). `ReplyConnectorView` spans the full row height.
   Non-last rows now draw the trunk top→bottom with the curve branching off it
   (previously the trunk also had a 10dp hole between `curveY-radius` and `curveY`).
2. **Line not connected to parent** — `item_reel_comment.xml`: avatar is now in a
   vertical column with a `view_thread_trunk` (2dp) that fills the row below the
   avatar down to `container_replies`. Main row bottom padding 8dp → 0, moved to
   the body column's marginBottom so the trunk is not clipped.
   `container_replies` is now `ReplyThreadContainer`, which shows/hides the trunk
   with itself (so every expand/collapse/rebind path stays in sync); the fragment
   fades the trunk with the replies and the adapter resets it on recycle.
3. **Line too faint** — alpha 40% → 60% (0x99); arrow head 3dp → 4dp.
4. **Dark mode** — new `thread_line` colour (feature-reels values + values-night):
   light `#9994A3B8`, dark `#99A1A7B3`. No longer uses `text_muted` (`#555555` in
   app/core night).
5. **Tighter curve** — radius 10dp → 8dp, arm 22dp → 20dp. Connector width
   28dp → 24dp so the arrow tip sits ~3dp from the reply avatar (with arm 20dp
   and width 28dp the gap would have grown to 7dp). Reply avatar shifts 4dp left.

## Implementation note
Trunk + branch + arrow head are one `Path` in one `drawPath`, so the translucent
stroke is a union (no darker dots where segments overlap).

## Files
- feature-reels/.../comments/ReplyConnectorView.java
- feature-reels/.../comments/ReplyThreadContainer.java (new)
- feature-reels/.../comments/ReelCommentFragment.java
- feature-reels/.../comments/ReelCommentsAdapter.java
- feature-reels/src/main/res/layout/item_reel_reply.xml
- feature-reels/src/main/res/layout/item_reel_comment.xml
- feature-reels/src/main/res/values/colors.xml, values-night/colors.xml

## Follow-up: 1dp stroke
Trunk, curve, arm and arrow head are now 1dp (was 2dp); parent `view_thread_trunk`
is 1dp too. Connector trunk x is fixed at 1dp so it stays aligned with the parent trunk.
