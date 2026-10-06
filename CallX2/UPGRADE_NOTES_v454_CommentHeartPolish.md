# v454 — Reel comment heart (like) polish

Files: `ReelCommentsAdapter.java`, `ReelCommentFragment.java`, `item_reel_comment.xml`, `item_reel_reply.xml`

1. **Color** — liked heart now brand `#FF416C` (was `holo_red_light`); unliked uses `@color/text_muted` (theme-aware, was `darker_gray`). Removed the conflicting `android:tint` from both layouts.
2. **Animation** — single tap now pops the heart (and slides/fades the count). Same for reply hearts. Double-tap on an already-liked comment only pulses.
3. **Tap target** — comment heart 32dp -> 44dp, reply heart 26dp -> 40dp (icon size and position unchanged via padding + negative margins).
4. **Count format** — replies now use `ReelCommentsAdapter.formatCount` (1.2K / 3M) like top-level comments.
5. **Accessibility** — contentDescription toggles "Like comment" <-> "Unlike comment" (and reply).
6. **Rollback** — optimistic like flip is reverted if the `likedBy` write or `likesCount` transaction fails. Reply likes are now optimistic too (instant, no wait for reload).

Shared helper: `ReelCommentsAdapter.applyHeartState(...)`.
