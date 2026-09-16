# Advance #5 — HTTP Range early preview (progressive JPEG)

## What
Before the real full-image download starts, fire a tiny parallel HTTP GET
with a `Range: bytes=0-28671` header (~28KB) against the same
`fl_progressive,f_auto,q_auto` Cloudinary URL already used for the
sharpen-while-downloading partials (`deriveProgressiveFullUrl`). A
progressive JPEG's first scans (DC + a couple of low-frequency AC passes)
live in those first few KB, so if enough of them landed inside the range,
`BitmapFactory.decodeByteArray` (inSampleSize=4, cheap) can already produce
a noticeably sharper frame than the ThumbHash placeholder — well before the
real download reaches its own first 20% milestone.

## Where
- **`MediaCache.fetchEarlyPreview(url, EarlyPreviewCallback)`** (new): opens
  the Range request on the existing `sPool`, reads up to the cap (handles
  both `206 Partial Content` and a CDN that ignores Range and just serves
  `200`), decodes, posts the bitmap back on `sMain` if — and only if —
  decode succeeded. Best-effort: any failure (timeout, truncated scan that
  won't decode) just means the callback never fires — no error path, the
  ThumbHash placeholder / real download's own partials cover it exactly as
  before this existed. Never called for Media-E2E images (ciphertext isn't
  decodable), same restriction as `deriveProgressiveFullUrl`.
- **`MessagePagingAdapter`**: wired into both places that already build a
  `tapFetchUrl`/`autoFetchUrl` progressive URL (manual tap-to-download +
  auto-download-on-WiFi) — fires `fetchEarlyPreview` in parallel with (not
  blocking) the real `getWithProgress` call. A local `boolean[] …FullyLoaded`
  flag (set by the 20/45/70% partials and by `onReady`) guards the early
  preview's callback so a slow-to-decode Range response can never land
  *after*, and overwrite, an already-sharper frame — race-safe by
  construction. Same `h.canvasBindToken != myToken` stale-row guard as
  every other async callback in this file.

## Net effect
Placeholder chain is now: ThumbHash blur → (parallel, whichever finishes
first) Range preview → 20/45/70% in-download partials → full sharp image.
No change to the actual downloaded/cached file or its bytes — this is a
pure preview-timing improvement, zero extra permanent data cost (the ~28KB
Range read is throwaway, not written to disk).
