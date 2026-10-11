# v532 — Status carousel polish (4) — baaki sab

- **Snap:** `StatusCardStartSnapHelper` — scroll/fling ke baad pehla card start padding se flush settle hota hai (aadha-kata card nahi). List ke end pe snap nahi, last card wapas nahi khichta.
- **Edge fade:** carousel RV pe horizontal fading edge (20dp). Right/left edge pe cards halke fade hote hain jab aur content ho. End spacing pehle se 16dp (10dp RV padding + 6dp card margin), title ke start ke barabar.
- **Seen/unseen animation:** `CardItem.onlySeenStateChanged` + DiffUtil payload. Ring aur card dim 240ms me fade hote hain (pehle turant badalte the). Mute/photo/etc change pe normal full bind.
- **Tablet:** card size ab `@dimen/status_card_width/height` (phone 116x182dp, `values-sw600dp` 140x216dp). Glide decode size aur prefetch bhi dimens se (hard-coded `CARD_W_DP/H_DP` hata). Skeleton cards bhi same dimens.
- **Large font:** fontScale >= 1.3 pe naam 1 line + text-card preview 3 lines; fontScale >= 1.8 pe time label hide (TalkBack description me time rehta hai).
