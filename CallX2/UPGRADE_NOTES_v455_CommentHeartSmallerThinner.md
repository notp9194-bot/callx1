# v455 — Comment heart: 50% smaller + thinner outline

Scope: reel comment system only (comment rows + inline replies). Shared `ic_heart` / `ic_heart_filled` (used elsewhere) are untouched.

- New drawables `ic_comment_heart.xml` (outline, stroke 1.4 vs 1.8 before, and it now scales down too) and `ic_comment_heart_filled.xml`.
- Comment heart icon 20dp -> 10dp, reply heart 16dp -> 8dp (padding-based, `scaleType=fitCenter`). Tap targets stay 44dp / 40dp.
- Like-count label moved up (-11dp top margin) to keep the same gap under the smaller heart.
- `ReelCommentsAdapter.applyHeartState` now uses the new drawables.
