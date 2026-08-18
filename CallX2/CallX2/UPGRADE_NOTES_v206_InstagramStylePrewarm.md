# v206 — Instagram-style next-reel pre-warming (instant swipe)

## Problem
Har reel pe swipe karte time thumbnail pehle dikhta tha, phir video —
kyunki `startPlayback()` sirf tabhi ExoPlayer banata tha jab reel actually
visible ho jaati thi (`applyVisibleState(true)`). Player build + prepare +
buffer karne mein jo waqt lagta hai, wahi gap thumbnail ke roop mein dikhta
tha — Instagram mein ye gap nahi dikhta kyunki wo agli reel ka player
pehle se ban chuka hota hai.

## Fix — next reel's ExoPlayer ab pehle se ban jaata hai (muted, paused)

**`ReelPlayerFragment.prewarmPlayer()`** (naya method):
- `preparePlayerSilently()` ko call karta hai — jo already ExoPlayer
  banata hai, cache-first media source attach karta hai, `prepare()` call
  karta hai — sab kuch **muted** (`setVolume(0f)`) aur **paused**
  (`setPlayWhenReady(false)`) rehte hue. Ye method pehle se exist karta
  tha, bas sirf tabhi call hota tha jab reel visible ho jaati.
- Skip ho jaata hai agar: reel already visible hai, Data Saver ON hai, ya
  network SLOW hai (kyunki prewarm ki guarantee nahi ki user wahan swipe
  karega hi — slow/limited connection ka budget waste nahi karna).

**`ReelsFragment.controlPlayback()`** — ab `activePosition + 1` (agli
reel) ke fragment pe `prewarmPlayer()` call karta hai, current reel ke
saath-saath. `offscreenPageLimit(3)` already set hai, isliye N+1 ka
fragment/view hamesha pehle se exist karta hai jab ye call hota hai.

## Peeche (previous reel) ke liye kuch nahi karna pada
Jab reel inactive hoti hai, `applyVisibleState(false)` sirf **pause**
karta hai — kabhi `releasePlayer()` nahi karta jab tak view hi destroy na
ho (offscreenPageLimit window ke bahar jaane par). Matlab peeche swipe
karna already instant tha — uska player already built + paused pada tha.
Sirf **aage** (kabhi na dekhi hui reel) ke liye hi player build karne ka
wait tha — wahi is fix ka target hai.

## Memory impact
Kisi bhi waqt max ~3 ExoPlayer instances alive rehte hain: previous
(paused, built), current (playing), next (prewarmed, paused) — ye
already-existing `offscreenPageLimit(3)` window ke andar hi hai, koi naya
resource ceiling nahi bada.

## Files changed
- `ReelPlayerFragment.java` — naya `prewarmPlayer()` method
- `ReelsFragment.java` — `controlPlayback()` mein next-position prewarm
  call add kiya

## Note
QoE dashboard ka "Time-To-First-Frame" number ab kabhi-kabhi bahut chhota
dikh sakta hai un reels ke liye jo prewarm ho chuki thi (kyunki buffering
prewarm ke time hi start ho gayi thi, swipe se pehle) — ye cosmetic hai,
actual experience genuinely fast ho gaya hai, bas stat ka context badal
gaya hai.

## Test steps
1. Reels tab kholo, WiFi/fast data pe, ek reel pe 2-3 second ruko.
2. Agli reel pe swipe karo — thumbnail flash nahi dikhna chahiye, video
   turant chalni chahiye.
3. Peeche swipe karo — wo bhi turant chalni chahiye (jaisa pehle tha).
4. Data Saver ON karke test karo — prewarm skip hona chahiye (Logcat mein
   `ReelPlayerController` ke "preparePlayerSilently" log ka timing check
   karo — visible hone ke baad hi aana chahiye, pehle se nahi).
