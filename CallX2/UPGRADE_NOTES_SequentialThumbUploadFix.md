# Non-E2E Image Thumbnail Race — Fixed via Sequential Upload

## Bug

The PARALLEL thumb+full upload change (WhatsApp-parity perf work) made the
thumbnail upload and the full-res upload fire at the same time on the same
uplink. On a slow/weak connection the two compete for bandwidth, and the
much smaller thumb can time out (`onError` → `pending.thumbnailUrl = null`)
while the bigger full-res upload still succeeds. The message then sends
with `mediaUrl` set but `thumbnailUrl` null — the receiver's bubble shows
no preview until the full-res image itself finishes downloading.

This only affected the **plaintext (non-E2E)** image path. E2E image sends
were already unaffected — they fold a small thumb into the encrypted key
envelope instead of uploading it separately (see `MediaE2ECrypto`), so
there's nothing to race.

## Fix

Reverted the plaintext path from PARALLEL back to SEQUENTIAL:
the small thumb uploads to Cloudinary **first**, and the full-res upload
only starts once that succeeds (or fails). Since the thumb is tiny, this
costs one small extra round-trip — not a meaningful delay — and it removes
the bandwidth race entirely, because the two uploads are never in flight
at the same time.

- Storage is unchanged: both thumb and full-res still upload to
  **Cloudinary** as plain (unencrypted) images; **Firebase** only ever
  stores the resulting `thumbnailUrl` / `mediaUrl` strings, exactly like
  before.
- If the thumb upload fails, the full-res upload still proceeds and the
  message sends without a preview — same graceful fallback as before.
- The E2E path (`thumbInlined` / envelope-embedded thumb) and the rare
  "E2E thumb too big to inline" fallback are untouched — they still run
  thumb+full in parallel, since that path already has no observed race
  issue in practice and isn't what was reported here.

`ChatMediaController#doStartImageUpload`'s non-E2E branch (the innermost
`else` after the `thumbInlined` / `isEncrypted` checks) is the only place
that changed.
