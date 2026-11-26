# v877 — Video bubble: sender + receiver dono side sirf 1 baar record (RenderNode)

Build/run nahi hua (Gradle + javac yahan nahi): sirf bracket-balance + manual review. Device pe test karo.
(v876 ke upar. v865 Bubble Perf report se verify karo.)

## Goal
Video bubble ka `Bubble.record` (outer RenderNode) sirf **first bind** pe ho. Uske baad bitmap / pill / scrim / label / duration
cached node ko dirty na kare.

## Kya badla

### Receiver
1. **Poster pre-warm** — `core/utils/VideoPosterPrewarm` (new). `MessageEntityMapper.toModel` (aur `GroupChatActivity` mapper) video row
   milte hi inline JPEG decode + ThumbHash L1 warm karta hai (background, idempotent). Bind pe poster ka sync hit.
2. **Cold bind** pe bhi single-bitmap: `applyVideoInlineJpeg` ab pehle `VideoPosterPrewarm.peek` -> pool -> `decodeNowIfSmall`
   (<=~9 KB JPEG, <1 ms, kill switch `VideoPosterPrewarm.SYNC_DECODE_ON_MISS`) se bind pass ke ANDAR hi bitmap set karta hai.
3. **ThumbHash vs poster** — poster mil gaya to ThumbHash lagta hi nahi (pehle ThumbHash -> poster = 2 records).
4. **Download gate live** — scrim + play glyph ab live overlay layer me (`MediaRenderer.drawMediaGateScrimLive`,
   `drawVideoPlayGlyphLive`). Captionless/voice-less bubble pe gate transition (idle->downloading->clear) = rect redraw, record 0.
   Captioned bubble pe caption row gate me chhupta hai (static) -> wahan full record rahega.
5. **Size label** (`getRemoteSize`) -> `setMediaDownloadIdleLabel()` rect-only (fallback: purana `setMediaDownloadGate`).

### Sender
6. **Local first-frame pool me** — `ChatMediaController.sendVideoLocalFirst` insert se pehle `MessagePagingAdapter.prewarmLocalVideoThumb`
   (bg). Pehli bind = sync pool hit (lazy Glide + 2nd record khatam).
7. **Duration insert se pehle** — `MediaMetadataRetriever` se `pending.duration` first bind me hi (badge baad me nahi aata).
8. **Upload finish** — `FLAG_MEDIA_READY` (mediaUrl DIFF me) -> `bindMediaReadyOnly` sirf gate clear (live). Full rebind nahi.
9. **Legacy thumb upload fallback** (E2E envelope bada / inline fail): sender ka `thumbnailUrl` local `file://` hi rehta hai;
   https `Message.thumbnailUrlRemote` (transient/@Exclude) me, `VideoInlineThumb.stripForWire` wire pe swap karta hai.
   `MessageMergePolicy`: echo me https aaye to local `file://` (agar file exist kare) rakhta hai -> file:// -> https flip + rebind nahi.

## Expected records
| Case | Pehle | Ab |
|---|---|---|
| Receiver, plaintext, inline poster (naya msg) | 3-5 | **1** |
| Sender (inline poster path) | 2-3 | **1** |
| Sender legacy thumb fallback | 3+ | 1 |
| Receiver legacy `thumbnailUrl` msg | 3-4 | 2 (placeholder -> real frame) |
| Receiver **E2E** video | 3-5 | 2 (poster envelope ke andar, async decrypt) |
| Captioned bubble gate transition | full | full (caption static) |

## Limits / dhyan do
- E2E receive me poster+ThumbHash encrypted envelope ke andar hain -> decrypt async; pre-warm yahan nahi hota (2 records).
- Purane `thumbnailUrl` wale msgs: real frame network/disk se aata hai -> 2 records (placeholder + real).
- Gate transition captioned/voice-strip bubble pe abhi bhi full record.
- `invalidate(Rect)` ke saath cached node ka hit rehta hai; overlay me scrim+glyph+pill har draw pe draw hote hain (cheap: roundRect + circle).

## Test (Bubble Perf: chat -> 3-dot -> Bubble Perf -> Copy)
1. Plaintext chat: video receive -> `Bubble.record` 1, `invalidate() on CLEAN bubble` me video setters nahi.
2. Video send (gallery + camera): bubble ratio + frame + duration badge ek saath; upload % tick pe record 0; finish pe gate chale jaye, flicker nahi.
3. Receiver: download pill -> tap -> % -> done: record 0 (captionless). Caption wala: expected 1-2.
4. Size label late aaye (slow net): bubble re-record nahi, sirf pill text.
5. Legacy: E2E envelope > 3800 (ya poster fail) -> sender bubble file:// rahe, receiver ko https thumb mile.
6. Offline send, retry failed upload, album, group video receive, scroll/fling: koi regression/stale scrim/ghost play glyph nahi.
7. Dark/light theme + RTL pe scrim/glyph position same (pehle cached node me tha, ab overlay me — same rect).
