# v858 — Contact/location expiry pill drawn live (on top of v857)

- Disappearing contact / location cards: the floating "⏳ mm:ss" corner pill (`drawCornerExpiryPill`) was baked into the cached node, so every 1s countdown tick re-recorded the whole card. It is now drawn from `drawDynamicOverlayLayer()` (ContactRenderer / LocationRenderer no longer draw it), and `computeLiveFooterRect()` returns the card's top-end corner band for contact/location, so `invalidateExpiryRegion()` redraws only that rect.
- Side effect: a delivery-status flip on a contact/location card now also uses the rect path (these cards draw no tick, so nothing visible changes).
- Everything else unchanged. Built without Gradle/SDK — run a normal build.
Test: send a contact and a location with disappearing messages on; the pill must count down in the card's top-end corner, and `Bubble.record` must not tick every second.
