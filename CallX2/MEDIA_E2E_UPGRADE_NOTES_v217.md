# Media E2E (image) — v215

Adds end-to-end encryption for 1:1 IMAGE messages, on top of the existing
1:1 text E2E (E2EEncryptionManager). Group chat and other media types
(video/gif/sticker/audio/file) are intentionally out of scope, matching
E2EEncryptionManager's own 1:1-only, text-only scope.

## New file
- `core/.../utils/MediaE2ECrypto.java` — chunk-wise streaming AES-256-GCM
  encrypt/decrypt (64 KB chunks, per-chunk authenticated IV, authenticated
  end-of-stream marker so truncation/tampering is always detected) +
  helpers to build/parse the small JSON key envelope (`{k, b}` = AES key +
  BlurHash) that travels through the existing text ratchet.

## How it works
1. **Send** (`ChatMediaController.doStartImageUpload` /`uploadFullImage`):
   after compression, the thumb + full JPEG are encrypted locally with a
   fresh random AES-256 key. The encrypted blobs are uploaded to
   Cloudinary as `resource_type=raw` (folders `callx/e2e_thumb`,
   `callx/e2e_image`) — Cloudinary never sees a decodable image. The AES
   key + BlurHash are packed into a JSON envelope and encrypted through
   `E2EEncryptionManager#encrypt` (same Double Ratchet session as text),
   stored as `Message#mediaKeyEnc`. `Message#blurHash` is left blank on
   these messages so no plaintext preview travels outside the envelope.
   If there's no E2E session yet for the partner, falls back to the old
   plaintext upload (mirrors `ChatActivity#doSendTextMessage`'s own
   fallback) so a photo is never silently dropped.
2. **Storage**: `Message`/`MessageEntity` gained a passthrough
   `mediaKeyEnc` column (Room v45→v46, `MIGRATION_45_46`). It's kept
   ratchet-encrypted at rest in Room — the AES key is only ever decrypted
   in memory, on demand, at render time.
3. **Receive/render**: `MediaCache` gained decrypting overloads of
   `get()`/`getWithProgress()` — when handed the AES key, downloaded
   bytes are streamed through `MediaE2ECrypto#decryptStream` as they're
   written, so the on-disk cache file holds plaintext. Everything
   downstream (Glide loading from that cache file, the bitmap pool, etc.)
   needed no changes. Wired into `MessagePagingAdapter` at:
   - the BlurHash placeholder (decrypts the envelope for the preview
     string instead of reading the now-blank `m.blurHash`)
   - the low-res thumbnail load (thumbnailUrl is ciphertext now, so this
     goes through decrypting `MediaCache.get` instead of `Glide.load(url)`
     directly)
   - the auto-download path (`MediaAutoDownloadPolicy`)
   - the manual tap-to-download path (`onMediaDownloadClick`)

   A SENT image's own bubble keeps rendering from the local plaintext
   file (`mediaLocalPath`) as before — no decrypt needed, matching the
   app's existing local-first send UX. Decryption is therefore only
   exercised on the receiving side.

## Server (`index-5-1.js`)
**No changes required.** `/cloudinary/sign` already signs generically off
`folder` + `timestamp`; `resource_type` isn't part of the signed payload,
so raw encrypted blobs upload through the existing endpoint untouched.
The server (and Cloudinary itself) only ever handles ciphertext bytes for
these messages.

## Known follow-ups (not covered in this pass)
The same `MediaCache.get(ctx, url, key, cb)` / `getWithProgress(...)`
pattern should be applied for full parity at a few remaining call sites
that also load a (potentially E2E) image URL directly:
- Full-screen image viewer / save-to-gallery flow
- Forwarded-message image preview
- Chat media gallery grid
- Push-notification image preview (`CallxMessagingService`)

Each is a mechanical change: resolve `MediaE2ECrypto.decryptKeyOnly(ctx,
m.mediaKeyEnc, m.senderId, messageId)` for a received message, pass it as
the new `decryptKey` param.

---

# Media E2E (video, thumbnail-only) — v216

Per explicit choice: **only the video thumbnail is E2E-encrypted; the
video file itself stays plaintext.**

## Why not full video E2E
This app's video pipeline leans on Cloudinary **server-side processing**
— HLS transcoding, multi-quality variants (480p/720p/1080p), adaptive
streaming (`eager` transforms, `q_auto` URLs). Cloudinary can only do
that if it can decode the actual video. Encrypting the video itself
before upload (like images) would mean losing all of that: single fixed
quality, full download-then-play instead of streaming/seeking. That
tradeoff was explicitly declined — thumbnail-only encryption still closes
the main leak (a compromised server/CDN being able to see a still frame
/ preview of every video sent) while keeping HLS/adaptive playback intact.

## Changes
- **`VideoUploader.java`**: new `upload(ctx, compressed, thumbFileOverride,
  thumbResourceType, callback)` overload — lets the caller substitute an
  already-encrypted thumbnail file + `resource_type=raw` for just the
  thumbnail upload, while the video upload path is completely untouched.
  Existing callers (reels, group chat) are unaffected — they still resolve
  to the old `upload(ctx, compressed, callback)` overload, unchanged
  behavior (`resourceType="image"`, no override file).
- **`ChatMediaController.doStartVideoUploadWork`**: encrypts
  `vr.thumbFile` into a temp `.enc` file with a fresh AES-256 key (reusing
  `MediaE2ECrypto`, same as images), wraps the key through the E2E text
  ratchet into `Message#mediaKeyEnc` (no BlurHash payload this time —
  video messages never had a BlurHash preview to begin with in this app).
  Falls back to plaintext thumb upload if no E2E session exists yet.
- **`MessagePagingAdapter`**: the video-thumbnail Glide load (in the
  `isVideo` bind branch) now decrypts via `MediaCache.get(ctx, url, key,
  cb)` when `m.mediaKeyEnc` is set, mirroring the image thumbnail fix.
  The actual video file download/play path is completely untouched —
  still plaintext, still streams/plays exactly as before.

## Known follow-up (video)
Only the single-video 1:1 send path (`doStartVideoUploadWork`) is wired.
The multi-select batch-video path (`uploadSequentially`) and the
audio-mixed-video override path (`doUploadWithOverride`) still upload
thumbnails in the clear — same mechanical fix applies (generate a key,
`MediaE2ECrypto.encryptFile` the thumb, pass it through the new
`thumbFileOverride` param) if/when needed.

---

# Media E2E (audio) — v217

Full E2E for 1:1 voice notes / audio messages — the whole audio file is
encrypted (unlike video, audio needs no Cloudinary-side transcoding, so
this follows the same full-file approach as images).

## Changes
- **`ChatMediaController.doUpload(Uri, String, String, String)`** — the
  shared upload path for `uploadAndSend(uri, "audio", ...)` (covers BOTH
  the mic-recording send button `finishAndSend()` and picking an audio
  file). Only branches on `"audio".equals(msgType)` — the `"file"` type
  that shares this same method is untouched. Reads the audio `Uri` into a
  temp file, encrypts it whole with `MediaE2ECrypto` (fresh AES-256 key),
  uploads that instead (already `resource_type=raw` for audio, so no
  Cloudinary-side change needed there), wraps the key through the E2E
  ratchet into `Message#mediaKeyEnc`. Falls back to plaintext if no E2E
  session exists yet.
- **`MessagePagingAdapter`**:
  - The bind-time preload warm-up (fires right when an audio bubble
    scrolls into view) now uses the decrypting `MediaCache.get(ctx, url,
    key, cb)` for E2E voice notes instead of `MediaStreamCache
    .preloadPartial` — MediaStreamCache's "stream the first 512KB for a
    fast start" trick has no way to decrypt on the fly, so an E2E voice
    note does a normal (still fast — voice notes are small) full
    download+decrypt instead.
  - `toggleAudio()` (the actual tap-to-play handler) does the same:
    resolves the message via `getItem(position)`, and for a received
    E2E voice note downloads+decrypts via `MediaCache.get` before calling
    the existing `playAudioFromPath()` — which itself needed NO changes,
    since it already just plays from whatever local file path it's
    handed.

## Not covered
The multi-select/batch upload path that also uploads audio as
`resourceType="raw"` (around `String resType = (isAudio || isFile) ?
"raw" : "image"` in `ChatMediaController`) is a separate code path from
`doUpload()` and still sends plaintext — same fix pattern applies if
needed later.


