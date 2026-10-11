# v499 — Status viewer bottom bar + HD status photos

**4. Bottom bar (Instagram-style)**
- Reply field: outlined 1dp pill (28dp radius), hint "Send message" (`status_reply_hint`).
- The "!"-in-a-circle icon (it was the emoji-reaction picker button) → smiley outline (`ic_status_react`). Same behaviour: opens the reaction sheet.
- Paper-plane (`ic_status_share`, outline): typed text → sends the reply as before; empty → shares the story via the existing forward sheet (`btnForward.performClick()`).
- Caption 14sp → 17sp, stronger shadow; it no longer collides with the reply pill (pill is below the card since v498).

**5. Photo quality**
- NewStatusActivity compresses status photos with ImageCompressor's HD tier (1920×2560 @ q92, ~3MB cap) instead of Standard (1280×1920 @ q80, 800KB cap). Applies to single + batch posts via `STATUS_PHOTO_HD`.
- Trade-off: bigger uploads (≤ ~3MB) on slow networks. Flip the constant to false to go back.
- Already-posted statuses are unchanged; a source photo that is itself low-res will still look soft.
