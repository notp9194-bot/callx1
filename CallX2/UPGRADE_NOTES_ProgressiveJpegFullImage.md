# Progressive JPEG full-image delivery — sharpen-while-downloading

## What was asked
Full chat image ke liye progressive JPEG/WebP, taaki download hote hote
image progressively sharp hoti jaye — ThumbHash blur se seedha crisp image
me jump karne ke bajaye beech me ek smooth "sharpening" transition dikhe.

## Why JPEG, not WebP
Progressive scan-based rendering (coarse-full-frame pass → sharper passes)
is a JPEG-specific bitstream structure. Android's lossy WebP encoder/decoder
is single-pass — a partially-downloaded WebP file just fails to decode at
all until the whole thing has arrived, so it can't give the "sharpens while
loading" effect the request was actually asking for. JPEG is therefore the
only one of the two that can deliver this.

## Design
1. **`CloudinaryUploader.deriveProgressiveFullUrl(secureUrl)`** — new helper,
   same on-the-fly-transform pattern as the existing `deriveThumbUrl()`.
   Inserts `fl_progressive,f_jpg,q_auto/` right after `/upload/` in the
   Cloudinary URL. No re-upload needed — Cloudinary generates and CDN-caches
   the progressive-JPEG variant the first time it's requested, same as any
   other on-the-fly transform already used in this codebase.
   - Trade-off, stated plainly: this forces JPEG over `f_auto`'s WebP/AVIF
     negotiation, so it costs some bandwidth savings on modern devices. Only
     applied to the full-image download URL (where the effect is visible and
     the download is slow enough to matter) — thumbnails keep `f_auto/webp`.
   - **Only used for plaintext (non-E2E) images.** A Media-E2E `mediaUrl` is
     encrypted raw bytes on Cloudinary — there's nothing for Cloudinary to
     re-encode as a progressive JPEG, and decrypting only *part* of an
     AES-GCM stream before the auth tag at the end verifies is a security
     anti-pattern, not just an engineering gap. E2E images keep the existing
     behavior: ThumbHash blur placeholder until the full decrypt+verify
     completes, no partial preview.

2. **`MediaCache`** — added a `getWithProgress(ctx, cacheKeyUrl, fetchUrl,
   decryptKey, expectedDigest, cb)` overload that downloads from `fetchUrl`
   (the progressive-JPEG variant) but keys the on-disk cache/dedupe off
   `cacheKeyUrl` (the message's real, unchanged `mediaUrl`). This matters:
   every other lookup in the chat code (`MediaCache.getCached`, the in-memory
   bitmap pool, `downloadingMediaUrls` dedupe, the tap-to-view/
   MediaViewerActivity intent) still looks the image up by the original
   `mediaUrl` — if the fetch URL and cache key were the same value, switching
   to the progressive variant would silently break all of those into a
   permanent cache-miss/re-download loop. All existing 2–4 arg overloads are
   unchanged and still behave exactly as before (they just default
   `fetchUrl = cacheKeyUrl`).
   - `ProgressCallback` gained one new **default** method,
     `onPartialBitmap(Bitmap partial)` — no-op unless a caller overrides it,
     so every existing audio/video/file/gif download callback needs zero
     changes.
   - The actual partial-decode logic lives in `downloadWithProgress()`'s
     plaintext byte-copy loop only (never the E2E/decrypt branch, per the
     security reasoning above): at three fixed milestones (20% / 45% / 70%
     downloaded), it flushes the in-progress temp file and attempts
     `BitmapFactory.decodeFile()` on it with `inSampleSize=4` (cheap —
     this is a transient preview, not the final render). Each attempt is
     wrapped in try/catch: a progressive JPEG cut off mid-scan is a normal,
     expected decode failure, especially at the 20% milestone — it just
     skips that preview and waits for the next milestone (or the final
     complete decode at `onReady`).

3. **`MessagePagingAdapter`** — wired the derived progressive URL +
   `onPartialBitmap` into the three places a chat image actually downloads:
   - the auto-download-on-WiFi path (canvas `isImage` block)
   - the manual "tap to download" path (`onMediaDownloadClick`)
   - the legacy voice-caption-on-photo bubble (`bindDownloadOverlay`) — fetch
     URL swapped for consistency, but no `onPartialBitmap` hookup since that
     bubble type has no live canvas bitmap to update mid-download, only a
     pill percentage (same as before).
   In the canvas path, `onPartialBitmap` calls `cv.setMediaBitmap(partial)` —
   the download gate (dim scrim + progress ring, from `setMediaDownloadGate`/
   `setMediaDownloadProgress`) is drawn as an overlay ON TOP of the media
   bitmap, not a replacement for it, so the partial preview shows through
   underneath the live progress percentage exactly as intended, then
   `onReady`'s full decode replaces it at 100%.

## Net effect
Received full-size chat images: ThumbHash blur (instant) → 1–3 progressively
sharper in-place previews as the progressive JPEG downloads → full crisp
decode on completion. Sent images and thumbnails are unaffected. E2E images
are unaffected (deliberately, for the auth-tag reason above).
