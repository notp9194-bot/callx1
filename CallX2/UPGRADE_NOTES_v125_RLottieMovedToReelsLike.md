# v125 — RLottie moved out of chat reactions, into the Reels like button

## What was asked
1. Remove RLottie completely from the chat long-press emoji reaction picker.
2. Put RLottie on the Reels like button instead.

## 1. Chat reaction picker — RLottie fully removed
`MessagePagingAdapter#showActionBottomSheetInner()` no longer creates any
`RLottieViewWrapper` for the 6 quick-reaction slots. Every slot is now
always the plain unicode-glyph view (`buildUnicodeReactionGlyph()`) with
the same staggered pop-in / tap punch-scale animation as before — that
code path already existed as the "should never happen" fallback, it's now
simply the only path. The per-dialog native-drawable release loop
(`lottieRefs` / `dlgLongPress.setOnDismissListener`) was removed along with
it, since there's nothing native left to release.

`ReactionEmojiCatalog`'s doc comment was updated to stop describing a
RLottie-backed picker that no longer exists — it's just an id→unicode
lookup table now.

The 6 bundled reaction animations
(`feature-chat/src/main/assets/lottie/reaction_{heart,thumb,laugh,wow,sad,angry}.json`)
were deleted — nothing in the app loads them anymore. The heart one was
kept (see below), everything else was simply unused weight.

`RLottieViewWrapper` / `RLottiePlaybackPool` / `EmptyChatLottieController`
(the empty-chat wave animation) were **not** touched — that's a different
feature from the reaction picker and wasn't part of the ask.

## 2. Reels like button — RLottie added
`fragment_reel_player.xml`'s double-tap like burst (`@+id/iv_like_anim`)
was a static `ImageView` (`ic_heart_filled` + scale/alpha `ObjectAnimator`).
It's now a `com.callx.app.views.RLottieView` that plays the same
hand-built heartbeat animation the chat reaction picker used to show
(copied to `feature-reels/src/main/assets/lottie/like_heart.json`), on
top of the same scale/alpha choreography as before.

`ReelSocialController.showLikeAnimation()` now calls
`ivLikeAnim.playFromAsset("lottie/like_heart.json")` right before the
existing `AnimatorSet` runs, so the burst plays the heartbeat once per
double-tap instead of just fading a static drawable in and out.

### New shared widget: `core/.../views/RLottieView.java`
`RLottieViewWrapper` lives in `:feature-chat`, and `:feature-reels`
doesn't depend on `:feature-chat` — so a new small twin,
`com.callx.app.views.RLottieView`, was added to `:core` instead (same
`com.aghajari.rlottie` API, verified against the same aar). `:core`
already exposes `rlottie.aar` as an `api` dependency and `CallxApp`
already calls `AXrLottie.init()` once at startup, so no new native-init
wiring was needed — `:feature-reels` already transitively has everything
it needs by depending on `:core`.

`ReelSocialController.release()` (already called from
`ReelPlayerFragment#onDestroyView()`) now also calls
`ivLikeAnim.release()`, so the native drawable doesn't leak on every reel
swipe/recycle.

## Files changed
- `feature-chat/.../MessagePagingAdapter.java` — reaction picker: RLottie
  path removed, unicode-only now.
- `core/.../utils/ReactionEmojiCatalog.java` — doc comment updated.
- `feature-chat/src/main/assets/lottie/reaction_*.json` — deleted (6 files).
- `feature-reels/src/main/assets/lottie/like_heart.json` — new (copy of
  the old `reaction_heart.json`).
- `core/src/main/java/com/callx/app/views/RLottieView.java` — new shared
  RLottie widget.
- `feature-reels/src/main/res/layout/fragment_reel_player.xml` —
  `iv_like_anim` is now a `RLottieView`, not an `ImageView`.
- `feature-reels/.../controllers/ReelSocialController.java` —
  `ivLikeAnim` field retyped, `showLikeAnimation()` triggers the RLottie
  playback, `release()` releases its native memory.
