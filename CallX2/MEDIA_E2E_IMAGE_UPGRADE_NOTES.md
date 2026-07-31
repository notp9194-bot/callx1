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
