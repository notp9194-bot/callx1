# v467 — Reel comments: input bar polish

1. Bottom-aligned row: `row_comment_input` gravity center_vertical -> bottom; field minHeight 40dp
   (= button size) so single-line still lines up. Multi-line keeps avatar/photo/send next to the last line.
2. Send exists only when there is something to send (text or picked photo): hidden (GONE) otherwise,
   filled brand-colour circle (`bg_comment_send_circle`, 36dp visible in a 40dp target) with white arrow,
   overshoot pop on appear. Row has a LayoutTransition (150ms, incl. CHANGING) attached AFTER the first
   silent state apply, so the field resizing / send fading / photo-button hiding (reply mode) animate and
   opening the sheet doesn't. `comment_send_icon` colour is now unused (kept).
3. Own avatar (`iv_my_avatar`, 32dp circle) at the left of the bar, bound via ReelCommentAvatarBinder
   (TINY tier = 32dp, same as comment rows). Paints the Auth photo immediately, upgrades to the reels
   photo when loadMyPhoto() returns; placeholder ic_person otherwise.

Files: activity_reel_comment.xml, bg_comment_send_circle.xml (new), ReelCommentFragment.java, version.properties (125).
