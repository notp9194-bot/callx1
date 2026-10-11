# v529 — Status carousel polish

1. **Bottom scrim:** `bg_status_card_bottom_scrim.xml` (76dp, #B3000000 -> transparent) `item_status_card.xml` me add. Naam ab bright photo pe bhi readable.
2. **Image crossfade:** `StatusCardAdapter.onBindViewHolder` me Glide `.transition(withCrossFade(150))`, flat placeholder se pop-in nahi hota.
3. **Alignment:** `item_status_carousel.xml` RV `paddingStart` 4dp -> 8dp, pehla card (8dp margin) ab title (16dp) ke saath align.
