# v611 — Media bubble bitmap pipeline (items 1–4)

Static changes only. Not compiled and not profiled here: build it and take a
Perfetto / JankStats scroll trace before and after.

## 1. App-owned decode pipeline (no main-thread cache copy)
New `conversation/BubbleBitmapPipeline.java`.
- `putBitmapSafely()` used to `Bitmap.copy()` every Glide result on the main thread
  (Glide's bitmaps belong to its BitmapPool). Bitmaps decoded by the pipeline come
  straight from BitmapFactory, so they are cached as-is (`isOwned()`), no copy.
- Decode runs on a 2-thread LIFO executor (newest bind first). A decode whose holder was
  already recycled (token mismatch) is skipped before any pixel work.
- Sizing matches Glide's CENTER_OUTSIDE (>= target on both axes, never upscaled),
  inSampleSize + exact residual scale, EXIF orientation for JPEG, opaque JPEG -> RGB_565.
- inBitmap reuse pool (4 MB) is fed only by bitmaps nobody can reference: EXIF
  intermediates and duplicate decodes that lost a race with preload. Bitmaps in the
  LruCaches are never recycled/reused on eviction (a view may still draw them — that is
  the "recycled bitmap" crash class from before).
- Converted call sites (local File / file Uri only): single image bind, local pending
  upload preview, manual-download onReady, auto-download onReady, E2E video thumb file.
  On decode failure each falls back to the old Glide path. Remote URL thumbs (reply,
  seen, reel, location, link, grid, GIF/sticker) still use Glide + pooled copy (the copy
  is skipped when an equal-size entry is already cached).
- `dashboardRecordDecoded()` JPEG-encodes owned bitmaps on DASHBOARD_DISK_EXECUTOR
  instead of the main thread (disk exists() stat moved too).

## 2. Placeholder blur cache (MediaRenderer)
- Process-wide `LruCache` keyed by (placeholder bitmap identity, bucketed blur radius,
  native-vs-java). Blur now runs on a background thread; a miss draws the raw 32x32
  placeholder for a frame, then the bubble invalidates itself. Waiters for the same key
  share one compute. Failures are negative-cached.

## 3. Bind work off the main thread
- `MessagePagingAdapter.warmMediaBindCaches(Message)` is called from
  `ChatActivity` / `GroupChatActivity` `buildModelUncached()` (already on ioExecutor):
  warms the received-image "file exists" memo and the ThumbHash L1 cache.
  Caption StaticLayout and aspect ratio were already precomputed there.
- Look-ahead preloader (scroll listener attached in `onAttachedToRecyclerView`): decodes
  the next 6 received images (3 on low-RAM) into MEDIA_BITMAP_CACHE in scroll direction,
  80 ms throttle, bounded queue. Uses the same key/size as bind, so a bind hits the pool.
  This replaces RecyclerViewPreloader on purpose: that one fills Glide's cache, but these
  paths read our own pool.

## 4. Bitmap.Config.HARDWARE
- Pipeline decodes display-only photos straight to HARDWARE on API 28+ (target >= 128 px,
  normal EXIF orientation). Upload happens on the decode thread. Any failure -> software.
  Kill switch: `BubbleBitmapPipeline.ENABLE_HARDWARE`.
- Placeholder blur, EXIF-rotated images and API < 28 stay software (readback / transform).
- Verified no pixel readback or software-canvas draw of the media bitmap in the chat module.

## Not done
5 (RecyclerView tuning), 6 (shared spinner clock), 7 (baseline profile / macrobenchmark).
