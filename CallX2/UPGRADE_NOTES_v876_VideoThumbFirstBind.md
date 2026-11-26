# v876 — Video send: thumbnail ab bubble ke saath hi dikhta hai (reopen ki zaroorat nahi)

Build nahi chala (Gradle yahan nahi): sirf `javac` syntax parse. Device pe test karo.
(v874 ratio fix + polish, v875 hot-path optimization ke upar. v875 ka "bubble pehle, thumb baad me" ordering is release me badla gaya.)

## Bug
Video bhejne ke baad bubble sahi ratio me banta tha par thumbnail nahi dikhta tha; chat reopen par dikhta tha.

## Root cause
`MessagePagingAdapter.DIFF.areContentsTheSame()` sirf kuch "critical" fields compare karta tha — `thumbnailUrl` / `mediaWidth` /
`mediaHeight` nahi. Sender ka local first-frame (`file://`) bubble insert ke BAAD Room me aata tha -> DiffUtil "same" bol deta tha
(rebind nahi). Baad me status uploading->sent hone par sirf `FLAG_STATUS` ka partial bind chalta tha (ticks), thumbnail phir bhi nahi.
Reopen par list Room se fresh bind hoti hai, isliye tab dikhta tha.

## Fix (dono layers)
1. **Adapter (asli fix):** `areContentsTheSame` + `getChangePayload` me `thumbnailUrl`, `mediaWidth`, `mediaHeight` add.
   Inme se koi badle to full rebind (status-only fast path nahi). Receiver/late poster/late dims sab ke liye bhi sahi.
2. **Send pipeline (`ChatMediaController.sendVideoLocalFirst`):** ek hi file-open me bounds + first-frame (scaled decode)
   nikalke bubble insert KARNE SE PEHLE `pending.mediaWidth/Height` + `pending.thumbnailUrl` set — pehli bind me hi ratio + thumbnail,
   koi pop-in/rebind nahi. Upload insert ke baad hi start.
3. **Deadline 350 ms:** slow URI par bubble ruk nahi'ta; thumb/ratio baad me aaye to Room me persist (`updateThumbnailUrl` /
   `updateMediaDimensions`) aur ab DIFF fix se rebind bhi hota hai.
4. Bonus: offline send ke bubble me bhi ab first-frame hota hai (insert ke saath persist; `file://` wire pe nahi jaata — v873 strip).

## Trade-off
Bubble ab bounds + first-frame ke baad aata hai (scaled decode: tez, par kuch ms) — Telegram jaisa "poora bubble ek saath".
Slow case me 350 ms cap.

## Test
1. Gallery + camera video bhejo: bubble turant ratio + thumbnail ke saath, "Compressing" ke time bhi frame dikhe; upload ke baad na gayab na flicker.
2. Chat reopen kiye bina thumbnail rahe.
3. Slow/cloud URI: bubble <=350 ms, thumb baad me khud aa jaye (reopen nahi).
4. Offline video, retry failed upload, album (multi-select), received video (thumb/blur normal), group chat video receive.
5. Scroll/fling perf: koi extra rebind storm nahi (sirf thumb/size actually badalne par rebind).
