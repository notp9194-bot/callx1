# v67 — Non-E2E Inline Thumbnail (fixes parallel thumb+full upload race)

## Bug

The previous WhatsApp-parity change made the thumb upload and the full-res
upload fire **in parallel** (instead of thumb-then-full) for faster sends.
On a slow/weak connection, the small thumbnail upload can lose the
bandwidth-contention race against the much bigger full-res upload and time
out (`onError` → `pending.thumbnailUrl = null`), while the full-res upload
still succeeds. The message gets sent with `mediaUrl` set but
`thumbnailUrl` null — the receiver's bubble shows no preview until the
full-res image itself finishes downloading, even though a low-res preview
should have been available immediately.

This only affected **non-E2E** image sends (1:1 chats with no E2E session
yet, and the "E2E encrypt failed" plaintext fallback). E2E image sends were
already immune: `MediaE2ECrypto.shouldInlineThumb` folds a small thumb
directly into the encrypted key envelope (`Message#mediaKeyEnc`) instead of
uploading it separately — mirroring WhatsApp, which never uploads a
thumbnail as its own network blob at all.

## Fix

Applied the same WhatsApp approach to the non-E2E path:

- New field `Message#thumbInlineData` — base64-encoded bytes of the small
  (≤48 KB, same cap as `MediaE2ECrypto.INLINE_THUMB_MAX_BYTES`) compressed
  JPEG thumbnail, embedded directly in the plaintext message field.
- `ChatMediaController#tryInlinePlaintextThumb` checks the thumb file
  against that size cap and, if it fits, base64-encodes it into
  `pending.thumbInlineData` instead of starting a second network upload.
  Both non-E2E code paths (no E2E session yet; E2E-encrypt-failed fallback)
  now call this and skip straight to the existing `thumbInlined` branch
  that only uploads the full-res image — same code path E2E sends already
  use, just without a decrypt step needed on either end.
- `MessagePagingAdapter`'s image-bind logic now checks
  `m.thumbInlineData` (when there's no `mediaKeyEnc`) the same way it
  already checks the E2E envelope's inline-thumb slot, decoding it in
  memory with zero network round-trips.
- If the compressed thumb is unusually large (rare) it still falls back to
  the old separate Cloudinary thumb upload, same as the E2E path's own
  fallback.

Net effect: a non-E2E image send now only ever makes **one** network
upload (the full-res image) — there is no second, smaller upload left to
lose a bandwidth race, so the receiver's bubble can no longer end up with
a missing thumbnail while the full photo loads fine.

## Storage

- `Message#thumbInlineData` (new field, plaintext base64, Firebase field).
- `MessageEntity#thumbInlineData` (new Room column).
- `AppDatabase` bumped to version 67; `MIGRATION_66_67` adds the
  `thumbInlineData TEXT` column to the `messages` table.
- Wired through `MessageEntityMapper`, `ChatMessageSender`, and
  `GroupChatActivity`'s entity mapping for Room round-trip parity (group
  image sends don't currently do a separate thumb upload at all, so they
  weren't affected by the race, but the field is wired through for
  consistency).

## Out of scope

- Video thumbnails (uploaded sequentially before the video, not in
  parallel — no race to fix there).
- The multi-image "share several photos at once" batch-send path (thumb
  is uploaded, then chained to the full upload — sequential, not
  parallel — also no race).
- Forwarding a plain (non-E2E) image message doesn't currently carry
  `thumbnailUrl` over at all — a pre-existing gap unrelated to this fix,
  left untouched here.
