# v207 — Instagram-se-bhi-upar reel prewarming (6 advanced steps)

v206 ne sirf N+1 ka ExoPlayer pehle se bana diya tha. Ye upgrade wahi
foundation le kar 6 aur advanced steps add karta hai:

## 1. N+2 bhi real player prewarm (sirf bytes nahi)
`ReelsFragment.controlPlayback()` ab N+1 ke saath-saath N+2 (aur adaptive
distance ki wajah se kabhi N+3) ke fragment pe bhi `prewarmPlayer()` call
karta hai — matlab ek nahi, do-teen reels aage tak **actual ExoPlayer**
already built + buffered milta hai, sirf preload bytes nahi.
`offscreenPageLimit` 3 se 4 kiya gaya taaki N+3 ka fragment bhi zinda rahe
jab fast-scroll adaptive distance usko maange.

## 2. Adaptive prewarm distance — swipe velocity se
`ReelsFragment` mein `onPageScrolled()` already px/ms scroll velocity
calculate kar raha tha (predictive preloader ke liye) — usi
`lastScrollVelocity` ko ab `controlPlayback()` bhi use karta hai:
- Fast flick (>3.5 px/ms) → N+1, N+2, N+3 teeno prewarm
- Moderate scroll (>1.2 px/ms) → N+1, N+2
- Slow/settled → sirf N+1 (jaisa v206 mein tha)

Har extra step (N+2 se aage) `PrewarmThrottleGuard.shouldThrottleExtraDistance()`
se gated hai — device garam/battery kam ho to sirf N+1 tak hi rukta hai.

## 3. Player pool reuse — naya `ExoPlayerPool`
`feature-reels/.../player/ExoPlayerPool.java` (naya file): 4 ExoPlayer
instances ka chhota pool. Pehle har reel apna naya ExoPlayer banata tha
aur purana `release()` kar deta tha — codec/renderer/internal-thread setup
har baar se-se hota tha. Ab:
- `ReelPlayerController.preparePlayerSilently()` → `ExoPlayerPool.acquire()`
  se ek already-built instance leta hai (ya pool khaali hone tak naya
  banata hai), sirf naya `MediaSource` + quality-cap track-selector params
  attach karta hai (`AdaptiveStreamingManager.attachToPlayer()`, naya
  method).
- `ReelPlayerController.releasePlayer()` → ab `player.release()` nahi
  karta, `ExoPlayerPool.release()` ko wapas kar deta hai reuse ke liye.
- Listeners ko pool track karta hai (`trackListener()`) aur har reuse pe
  hata deta hai — purani reel ka listener nayi reel pe leak nahi hota.
- `ReelsFragment.onDestroyView()` (poora Reels feature band hone par)
  `ExoPlayerPool.releaseAll()` call karta hai — tab hi asli
  `player.release()` hota hai.

## 4. First-frame pre-render
`feature-cache/.../ReelFirstFrameCache.java` (naya file):
`MediaMetadataRetriever.getFrameAtTime(0)` se video ka pehla frame ek
chhoti bitmap (480px wide) mein background thread pe decode karke LRU
cache (6 entries) mein rakhta hai — sirf tab jab video already
substantially local cache mein ho (cold network URL pe decode nahi karta,
warna khud hi ek extra network round-trip ban jaata). Agar ye frame
player ke `STATE_READY` hone se pehle ready ho jaaye,
`ReelPlayerController` ivThumb ka bitmap isi decoded frame se badal deta
hai — thumbnail→video transition mein koi visible "jump" nahi rehta.

## 5. Predictive prefetch — ab player-level tak
`ReelPredictivePreloader` mein naya `PlayerPrewarmListener` interface:
model ka top-ranked candidate (affinity × transition-probability ×
position-decay score) sirf bytes preload nahi karta — agar us reel ka
fragment already ban chuka hai (offscreenPageLimit window ke andar),
`ReelsFragment` uske liye bhi real `prewarmPlayer()` call karta hai. Ye
purely additive hai — positional N+1/N+2/N+3 prewarm ke upar ek bonus,
kabhi extra fragment force nahi karta.

## 6. Battery/thermal aware throttling
Naya `PrewarmThrottleGuard` (`feature-reels/.../player/`):
- `shouldThrottle()` — power-save mode ON, ya battery ≤15% (non-charging),
  ya thermal status SEVERE+ (API 29+) → **sab** prewarm (even N+1) skip.
- `shouldThrottleExtraDistance()` — thermal MODERATE+ pe bhi trigger, sirf
  N+2/N+3 jaisa "extra" prewarm rokta hai, N+1 (jo actual flash hataata
  hai) chalta rehta hai jab tak severe na ho jaaye.

Dono guards `ReelPlayerFragment.prewarmPlayer()`,
`ReelsFragment.controlPlayback()`, aur predictive player-prewarm callback
mein wire kiye gaye hain.

## Files changed / added
- **Naye:** `player/ExoPlayerPool.java`, `player/PrewarmThrottleGuard.java`,
  `cache/ReelFirstFrameCache.java`
- **Modified:** `AdaptiveStreamingManager.java` (buildBarePlayer,
  attachToPlayer, attachListener refactor), `ReelPlayerController.java`
  (pool-based preparePlayerSilently + releasePlayer, first-frame hook),
  `ReelPlayerFragment.java` (throttle guard in prewarmPlayer),
  `ReelsFragment.java` (offscreenPageLimit=4, adaptive distance,
  predictive player-prewarm wiring, pool teardown),
  `ReelPredictivePreloader.java` (PlayerPrewarmListener).

## Known limitation (documented, not fixed here)
`ReelPlayerController.switchToQuality()`'s legacy (non-HLS) quality-switch
path still does a direct `buildPlayer()` + `release()` instead of going
through the pool — most reels are HLS now (in-place track-selector switch,
no rebuild at all, see the code comment there), so this only affects
pre-HLS/legacy reels on a manual quality change, not the swipe-prewarm
path this upgrade targets. Left untouched to avoid touching the
watch-history/progress-tracking bookkeeping that's interleaved with it.

## Test steps
1. WiFi pe reels tab kholo, ek reel pe ruko — Logcat mein `ExoPlayerPool`
   ke "reused pooled instance" log dikhna chahiye N+1 warm hote waqt.
2. Fast flick karo 4-5 reels — Logcat mein prewarm distance 2-3 tak
   badhta dikhna chahiye (`ReelsFragment` controlPlayback ke naye logs
   via existing "Predictive preload" log ke aas-paas dekh sakte ho).
3. Battery Saver ON karke fast flick karo — extra distance (N+2/N+3) ruk
   jaana chahiye, sirf N+1 chalu rehna chahiye.
4. Reel pe ruk kar dekho ki thumbnail→video transition mein koi flash/jump
   na ho (first-frame pre-render kaam kar raha ho to).
5. Reels tab se bahar (Home tab) jaake wapas aao — playback abhi bhi
   turant instant hona chahiye (pool warm rehta hai tab-switch pe, sirf
   feature hi band hone par release hota hai).
