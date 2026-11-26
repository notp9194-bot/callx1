# v875 — Video send hot path: fastest bubble + thumbnail (v874 ke upar)

Build nahi chala (Gradle yahan nahi): sirf `javac` syntax parse. Device pe test + Perfetto/logcat se measure karo.

## v874 recap (ratio fix + polish)
Pending video bubble ab real display ratio pe banta hai (rotation-aware), upload ke baad raw/un-rotated dims se flip nahi hota,
group chat message me bhi dims jaati hain, retry rows me dims fill hoti hain, compress/upload pill ke peeche play circle nahi.

## v875 optimizations (sirf 1:1 chat video send)
1. **Ek hi file-open:** pehle bounds ke liye 1 `setDataSource`, thumb ke liye 2nd. Ab ek retriever: bounds -> frame. (`VideoCompressor.displayBoundsOf(mmr)`)
2. **Bubble pehle, thumb baad me, parallel:** bounds milte hi bubble insert; thumb usi bg thread pe uske baad nikalta hai.
   Pehle thumb insert ke baad ek aur bg job me serial hota tha. Insert -> thumb -> upload ka ordering main-looper FIFO se guaranteed.
3. **350 ms deadline race:** slow / cloud URI par bubble kabhi 350 ms se zyada nahi rukta; deadline pe 4:3 pe insert, bounds aate hi
   real ratio + Room update (`updateMediaDimensions`).
4. **Scaled frame decode (API 27+):** `getScaledFrameAtTime` ~200px pe seedha decode — full-res 1080p bitmap (~8 MB) alloc + downscale nahi.
   Aspect container ratio se verify hota hai; mismatch (OEM rotation quirk) par classic full decode fallback.
5. **Thumb file name `nanoTime`:** same millisecond me 2 videos bhejne par file collision nahi.
6. Dedupe: `uploadAndSend` aur gallery single-item dono ek `sendVideoLocalFirst()` use karte hain.

## Behavior notes
- Offline send: pehle jaisa (outbox queue, thumb nahi). Insert fail par upload start nahi hota (toast sirf `uploadAndSend` path me, pehle jaisa).
- Retry path (`startVideoUploadLocalFirst`) ab bhi dims fill karta hai aur ab scaled-frame thumb use karta hai.

## Jaan-bujhkar nahi kiya
- `vid_thumb` cache cleanup: sender ke purane bubbles `file://` thumb Room me rakhte hain (v873) — prune karne se wo thumbs tootenge.
- Global metadata cache: camera same file path reuse karta hai -> stale ratio risk; compress ke multi-second ke saamne 1 MMR open ki bachat negligible.
- MediaStore WIDTH/HEIGHT fast-path: OEM-wise rotation inconsistent -> galat ratio ka risk.

## Test
1. Gallery + camera (rotation 90) portrait/landscape video: bubble turant sahi ratio, thumb jaldi, upload ke baad jump nahi.
2. 2 video lagatar bhejo (same ms) -> dono ka alag thumb.
3. Slow/cloud (Drive/Photos cloud-only) URI -> bubble <=350 ms me, phir ratio theek ho jaye.
4. Offline send, insert-fail (storage full simulate), retry failed upload, multi-select album (extractVideoThumb ab bhi kaam kare).
5. Android 23-26 aur 27+ dono par thumb sahi (aspect/rotation).
6. Logcat: `ChatMediaController` warnings "video open failed" / "extractVideoThumbFrom failed" na aayein.
