# v830 — Glide / bitmap pipeline trim (on top of v829)
1. `putBitmapSafely`: HARDWARE Glide bitmaps are no longer `copy()`'d on the main thread (GPU readback + re-upload) and not cached as-is (Glide recycles immutable bitmaps). Rebind falls through to Glide's memory cache (synchronous hit).
2. Media-group grid cells from a local file now decode through `BubbleBitmapPipeline` via `loadFileBitmap()` (owned bitmap, no copy); Glide fallback on decode failure.
3. `warmMediaBindCaches()` (IO thread, model build) now warm-decodes the first 8 (4 on low-RAM) already-downloaded RECEIVED images into MEDIA_BITMAP_CACHE with the same key/size as bind -> first on-screen bind is a pool hit. Budget reset in `onAttachedToRecyclerView`.
4. `attachMediaPreloader`: one look-ahead kick (both directions) after first layout, retried up to 6x/50 ms. Previously preload only ran on scroll.
5. `VISIBLE_EXEC` decode threads: 2 -> 3 (>=4 cores) / 4 (>=6 cores); stays 2 on few-core devices.
Not changed on purpose: Glide memory cache size (HW thumbs now rely on it), `DiskCacheStrategy.ALL` on THUMB_RGB565 (thumbs are small; switching to RESOURCE/AUTOMATIC would force re-fetches across different override sizes).
Not compiled offline — run CI.
