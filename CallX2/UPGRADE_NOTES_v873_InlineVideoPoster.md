# v873 — Chat video thumbnail: inline JPEG poster (WhatsApp-style, zero extra upload/download)

Device pe build/run nahi hua — neeche "Test" section dekho.

## Pehle kya hota tha (1:1 chat video send)
- Compress ke baad frame ka ~300–480px WebP alag se Cloudinary pe upload (`VideoUploader`, 0–20%).
- Receiver: ThumbHash blur -> phir Glide se `thumbnailUrl` download (extra HTTP call).

## Ab
- `VideoInlineThumb.buildInlineJpegB64()` — already-extracted `vr.thumbFile` se ~160px JPEG (q55→28, max ~6 KB), Base64.
- **Plaintext chat:** `Message.inlineThumbB64` me message doc ke andar.
- **E2E chat:** plaintext field wire pe NAHI jaata; encrypted key-envelope me `"jt"` key (`MediaE2ECrypto.buildKeyEnvelopeJsonInlineJpeg`). Pehle wala encrypted thumb upload bhi band.
- `VideoUploader.uploadWithoutThumb()` — sirf video upload, thumb upload nahi (`thumbUrl == null`).
- Receiver (`MessagePagingAdapter` video block): inline JPEG local decode (off-main, pooled) -> `setMediaBitmap(bmp, true)`; ThumbHash sirf tab jab poster abhi nahi aaya; real frame (legacy `thumbnailUrl`) aaye to wo upar. State `vThumbState` 0/1/2.
- Sender apna local first-frame `file://` rakhta hai (Room), wire se hataya jaata hai: `VideoInlineThumb.stripForWire/restoreAfterWire` (`ChatMessageSender.firebasePushMessage` + `OutboxSyncWorker.sendMessage`; `workers/SyncWorker` me file:// skip). Server echo pe `MessageMergePolicy` + `applyRealtimeUpdate` local `file://` thumb preserve karte hain.
- **Room migration nahi:** purana unused column `thumbInlineData` reuse (Message `inlineThumbB64` <-> entity `thumbInlineData`). DB version same (74).
- `ChatActivity` scroll-preloader: video ke liye ab raw mp4 URL fallback nahi (null => skip).

## Fallback
Inline JPEG ban na paaye to purana flow (thumb upload + `thumbnailUrl`) as-is chalta hai. Purane messages (jinme `thumbnailUrl` hai) bhi waise hi render hote hain.

## Scope / limits (dhyan do)
- Sirf **1:1 chat single video** (`ChatMediaController.doStartVideoUploadWork`). Group video send (`GroupChatActivity`), multi-media aur reels/status abhi purane flow pe hain. Group me *receive* shared adapter se inline support karta hai.
- `VideoCompressor` untouched (reels/status share karte hain) — wo apna frame abhi bhi nikalta hai; inline JPEG usi se banta hai (extra extraction nahi).
- Reply-preview me video ka thumb ab ThumbHash tak (inline JPEG wahan nahi lagaya).
- Naya message field `inlineThumbB64` Firebase me jaata hai — purane clients isse ignore karte hain, par unko naye video me sirf ThumbHash dikhega (thumbnailUrl nahi hai).

## Test
1. 1:1 plaintext chat: video bhejo -> receiver pe bubble instantly real frame; Cloudinary `callx/videos/thumb` me nayi file nahi.
2. E2E chat: same; Firebase message me `inlineThumbB64` NAHI hona chahiye, `mediaKeyEnc` hona chahiye.
3. Sender: bubble send ke baad bhi sharp frame, echo ke baad bhi.
4. Offline send -> online aane pe outbox se push: Firebase me `thumbnailUrl` me `file://` nahi.
5. Purana video message (thumbnailUrl wala) normal.

## v873b FIX — E2E video "send karte hi delete/fail" bug
**Wajah:** Firebase rules me `messages/$chatId/$msgId/mediaKeyEnc` ki limit **4000 chars** hai. Pehle E2E me ~160px poster (~8 KB Base64) envelope ke andar daala tha -> envelope limit se bahut bada -> Firebase write reject -> message fail/gayab.
**Fix:**
- E2E me sirf **tiny poster (~80px, <=1.4 KB)** envelope me jaata hai (`VideoInlineThumb.buildTinyE2eJpegB64`).
- Encrypted envelope ki length check: `> 3800` chars ho to inline band, **legacy encrypted-thumb upload** pe fallback (`MAX_E2E_ENVELOPE_CHARS`).
- E2E fallback me plaintext poster wire pe kabhi nahi jaata (`inlineThumbB64` null kiya).
- Plaintext chat me full ~160px poster as-is (us field pe koi rule limit nahi).
- Sender ka apna bubble: bada local mirror (`thumbInlineData`) wire se stripped.
Optional (baad me): rules me `mediaKeyEnc` limit badhao to E2E me bada poster bhi chalega.
