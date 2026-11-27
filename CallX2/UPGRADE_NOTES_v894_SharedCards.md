# v894 — Card bubbles ka child RenderNode (reel/contact/location/seen/poll/file/audio) + footer-only setters

Build/run nahi hua (Gradle + javac yahan nahi): sirf bracket-balance + manual review. Device pe test karo.
(v893 ke upar. `BubblePerfStats` me `missWhy` / `dirtiedWhileClean` se verify karo.)

## Goal
Late bitmap / vote / state flip pe poora bubble node re-record na ho. Sirf card ka child node re-record ho.

## Kya badla (`MessageBubbleCanvasView`)
Naya generic `cardNode` (child RenderNode), same pattern jo v883 (media) aur v893 (album) me hai:
`currentCardKind()`, `recordCard()`, `drawCardInChild()`, `trySwapCardFast()`.

| # | Bubble | Ab kya hota hai |
|---|--------|-----------------|
| 7 | Poll | vote (`invalidatePollRegion`), `setPollVoters`, `setPollVoterBitmap`, `setPollFooterTappable`, `clearPollVoters` -> sirf poll child re-record |
| 8 | Reel share / contact / seen / location | `setReelShareThumb/Avatar/UsernameBitmap`, `setContactAvatarBitmap`, `setSeenAvatarBitmap`, `setSeenThumbBitmap`, `setLocationMapBitmap` -> sirf card child |
| 9 | Audio | play/pause flip (`setAudioPlaying`) aur static row change (`invalidateAudioRow`) -> sirf audio child. Voice badge on photo (`invalidateVoiceBadgeRegion`) -> media child (`trySwapMediaBitmapFast`) |
| 10 | File | `setFileDownloadState` (transition) aur `setFileCached` -> sirf file child |

Common:
- `setSeenBy` / `clearSeenBy`: strip overlay layer me hai, ab node dirty sirf tab jab row height badli. Warna `invalidateOverlay()`.
- `setEdited`: footer text live hai, ab node dirty sirf tab jab size signature badla. Warna footer tap-rect refresh + overlay redraw.
- `releaseRenderCaches()` me `cardNode` ka display list bhi free hota hai.

## Jaan-bujhke nahi badla
- **#6 Forwarded**: plain-text bubble v893 me shared key me aa chuka hai. `setForwardedFrom` pehle se same-state no-op hai. Non-text bubble pe label outer node me baked rehta hai (rebind pe content badalta hai, to record zaroori).
- `setPinned`: label node me baked hai aur height badalti hai, toggle rare hai.
- `setBubbleTailShown`: bubble ka shape node me baked. Text bubble pe shared-node lookup se already sasta.
- `bind()`: identical rebind pe pehle se early return hai. Baaki invalidates content change pe hain, text pe shared-node lookup ban jate hain.
- `refreshThemeColors` / `onConfigurationChanged`: rare, theek.

## Safety
`trySwapCardFast()` tabhi chalta hai jab outer node valid ho, size same ho, layout pending na ho aur card kind same ho. Warna purana `invalidate()` path.
