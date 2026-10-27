# v683 — Reel status card polish (StatusViewerActivity)

- **Tap → reel:** header (avatar/name) + naya "▶ Reel ›" chip tap pe wahi reel khulta hai (`openReelFromCard`). Long-press pill bhi isi path pe.
- **Deleted reel:** `reels/{id}` exist nahi karta to toast "This reel is no longer available" (dead player nahi khulta). Network fail pe player hi handle karta hai.
- **Mute button:** card ke top-right (header ke neeche) pe, card ke saath move; card hatne pe purani jagah wapas.
- **Header layout:** reel card me row full card width, naam weight=1 + ellipsize, time row ke andar flush-right. Hardcoded `+56dp` margin hata.
- **Time refresh:** har 30s pe "5m/2h" update (`reshareTimeTick`).
- **Owner resolve:** blank header ki jagah "Reel" label; network fail pe 2 retry (2s/4s back-off), sirf "not found" cache hota hai.
- **Scrim:** 48dp/30% → 64dp/40%.
- **A11y:** row ka description "Open reel by X, posted 5m"; chip ka bhi.
- Files: `feature-status/.../viewer/StatusViewerActivity.java`, `res/values/strings_status_viewer.xml`.
- Not done: reel caption/music strip on card (status row me reel ka caption/music field nahi hai).

## v684 follow-up
- Reel card header: avatar hata, sirf username (username na ho to naam) top-left.
- Top-right: time-ago ki jagah reel duration (m:ss). Source order: status row `durationSec` → `reels/{id}/duration` → player duration; late aaye to 500ms poll se update.

## v685 — reel card polish #3
- Duration label = min(reel duration, 30s) → status jitna chalta hai wahi dikhta hai.
- "Reel" duplicate fix: owner resolve hone tak chip hidden; deleted reel pe chip "Reel unavailable".
- Firebase: poora `reels/{id}` node ki jagah sirf `uid/ownerName/ownerPhoto` child reads; exists check `limitToFirst(1)`.
- Tap targets: header 40dp, chip 36dp. Scrim 96dp. Duration ab pill badge. Username `@` ke saath.
- RTL: header/chip/mute/card/scrim `setMarginStart`. Long-press pill intentionally rakha (v682 feature).

## v686 — ReelCardMetaCache
- Naya `cache/ReelCardMetaCache` (static LRU 256 + TTL): reel owner (10m; unresolved 1m), exists (10m), duration (1h).
- StatusViewerActivity ke instance caches isi pe point karte hain → viewer band/open hone pe refetch nahi, header turant.
