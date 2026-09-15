# v407 — Chat video thumbnails: BlurHash parity with images (ultra-fast placeholder)

## The problem

Image messages already had the "ultra" thumbnail optimization: at compress
time `ChatMediaController` decodes the already-on-disk compressed thumb at
1/4 res and encodes it into a ~20-30 char `BlurHash` string (see
`core/utils/BlurHash.java` — the same from-scratch encoder reels already use
via `BlurHashBackfillWorker`/`ReelUiController`). That string rides inline in
the message doc (or inside the E2E key envelope for encrypted chats) and
decodes locally in ~1 ms, so the receiver sees a blurred color-accurate
preview the instant the bubble appears — no extra upload, no extra
download, no network round trip at all.

**Video never got this.** `doStartVideoUploadWork`'s comment literally said
*"just no BlurHash payload this time"* — every video bubble sat on a flat
grey placeholder until the Cloudinary thumbnail frame finished downloading,
even though `MessagePagingAdapter`'s render path already had a
`vBlurHash`/`BlurHashPlaceholder.get(...)` block sitting there ready to use
it. The wiring existed on the receive side; the sender just never populated
it.

## The fix

**Send side** (`ChatMediaController#doStartVideoUploadWork`):
- Reuses `VideoCompressor.Result#thumbFile` — the still-frame already
  extracted for the Cloudinary thumb upload — decodes it at
  `inSampleSize=4` and runs it through the exact same `BlurHash.encode(thumb,
  4, 3)` call the image path uses. Zero extra frame extraction, zero extra
  file I/O beyond a downsampled decode (~1-2 ms).
- E2E videos: the hash now rides inside the same encrypted key envelope as
  the thumb key (`MediaE2ECrypto.buildKeyEnvelopeJson` already accepted a
  `blurHash` param — it was just being passed `null` for video). Matches the
  image E2E path 1:1: `pending.blurHash` stays `null` in the clear once it's
  in the envelope.
- Plaintext videos (no E2E session yet) / thumb-encrypt failure: falls back
  to `pending.blurHash = blurHash` directly, same fallback the image path
  uses.

**Receive side** (`MessagePagingAdapter`):
- Plaintext path unchanged — `m.blurHash` renders synchronously like before.
- New `resolveVideoBlurHashAsync` (sibling of the existing
  `resolveThumbMediaKeyAsync`) decrypts the envelope off the main thread via
  `E2eeDecryptExecutor` — same v375 rule as the thumb-key resolve right next
  to it, never decrypt ratchet envelopes synchronously on the UI thread —
  and applies `BlurHashPlaceholder.get(...)` once the hash lands. Only
  fires for received E2E videos; sent videos and plaintext videos skip it.

## Why this is the right kind of "ultra" optimization

No new dependency, no new wire format, no extra bytes beyond the ~20-30 char
string the image path already proved out. It's the same trick
WhatsApp/Instagram use (progressive blur placeholder from a tiny inline
string) and it was one path away from already being consistent across the
whole chat media surface — this just closes that last gap so **every**
media bubble (image or video, encrypted or not) gets the instant
zero-network placeholder instead of only images getting it.
