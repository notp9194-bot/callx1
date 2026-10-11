# v530 — Status carousel polish (2)

- **Theme colors:** card border/ripple/body/placeholder/hidden-tile/glyph ab tokens hain (`status_card_*` in `feature-status/res/values` + `values-night`), Java ke hard-coded `#3A3A3A/#2A2A2A/#99FFFFFF` hata diye (`ContextCompat.getColor`). Text-status default bg dono themes me dark (white text ke liye).
- **Segmented ring:** naya `core/.../StatusSegmentedRingDrawable` — status count ke hisaab se arcs (seen = grey, unseen = story gradient). `CardItem.segTotal/segUnseen` (diff me included). 1 status ya >30 par purana full ring.
- **Muted badge:** card ke top-start me chhota mute icon (`iv_card_muted`).
- **Small:** title `@string/status_carousel_title`, video badge custom `ic_status_card_play` (deprecated `android:tint` hata), avatar elevation comment fix (card 3dp).
