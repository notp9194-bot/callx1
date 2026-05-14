# CallX2 — Video Compression v24 Upgrade Notes

## What's New in v24

### 15 New / Upgraded Features (All Production-Grade)

---

## 1. Multi-Codec Support (AV1 → HEVC → H.264)
**File:** `VideoCompressor.java`

| Codec | Android | Size vs H.264 |
|---|---|---|
| AV1 | Android 12+ | ~45% smaller |
| HEVC (H.265) | Android 10+ | ~35% smaller |
| H.264 (AVC) | All | Baseline |

Auto-detected at runtime. Falls back gracefully if HW encoder unavailable.

---

## 2. Quality Level Selector
**File:** `VideoQualityPreferences.java`, `VideoQualitySheet.java`

5 quality levels:
- **Low (360p / 400 kbps)** — ~1 MB/min
- **Standard (540p / 800 kbps)** — ~2 MB/min
- **HD (720p / 1.5 Mbps)** — ~3.5 MB/min
- **Full HD (1080p / 3 Mbps)** — ~7 MB/min
- **Original** — no compression

**Per-chat quality memory** — set once for a contact, remembers forever.

---

## 3. Quality Bottom Sheet UI
**File:** `VideoQualitySheet.java`, `bottom_sheet_video_quality.xml`

- Shows estimated file size per quality level
- Active codec display (AV1/HEVC/H.264 + HW/SW)
- WiFi-only HD toggle
- Data saver mode toggle
- "Remember for this chat" toggle
- Compression stats summary

**Integration:** Call `VideoQualitySheet.show(fm, chatId, durationMs, quality -> ...)`

---

## 4. Adaptive Bitrate
**File:** `VideoCompressor.java` → `calcAdaptiveBitrate()`

Bitrate auto-adjusts based on:
- Quality tier baseline
- Codec efficiency (AV1 ×0.55, HEVC ×0.65)
- Original video bitrate (never exceeds source)
- Pixel ratio (sqrt scaling for perceptual quality)

---

## 5. Hardware Encoder Check
**File:** `VideoCompressor.java` → `hasHardwareEncoder()` / `hasAnyEncoder()`

Checks `MediaCodecList` before attempting each codec. Falls back to H.264 on failure. Never crashes with unsupported codec.

---

## 6. Chunked Cloudinary Upload (5 MB chunks)
**File:** `VideoUploader.java`

- Files > 5 MB → chunked upload with `X-Unique-Upload-Id` + `Content-Range`
- Resumable: if network drops mid-upload, next retry continues from last chunk
- Small files → direct single-part upload (no overhead)

**WorkManager worker also updated** (`VideoUploadWorker.java`)

---

## 7. Upload Pause / Resume / Cancel
**File:** `VideoUploader.java`

```java
VideoUploader uploader = VideoUploader.upload(ctx, result, callback);
uploader.pause();    // pause between chunks
uploader.resume();   // resume
uploader.cancel();   // abort
VideoUploader.cancelActive(); // cancel whatever is running
```

---

## 8. Progress Notification in WorkManager
**File:** `VideoUploadWorker.java`

- Shows `ForegroundInfo` with % progress in system notification
- Cancel button in notification
- Done notification with compression summary
- Error notification with tap-to-retry
- Separate notification channel: `callx_video_upload`

---

## 9. Per-Quality WorkManager Support
**File:** `VideoUploadWorker.java`

```java
VideoUploadWorker.enqueue(ctx, path, chatId, msgId, isGroup, Quality.HD);
```

Quality stored in `KEY_QUALITY` input data. Default: STANDARD.

---

## 10. Video Trim Activity
**File:** `VideoTrimActivity.java`, `activity_video_trim.xml`

- Dual SeekBar for start/end trim points
- Live VideoView preview
- Frame-accurate trim via MediaExtractor + MediaMuxer
- Returns trimmed file path via `setResult`
- Original file never modified

**Register in manifest:**
```xml
<activity android:name=".activities.VideoTrimActivity" android:exported="false"/>
```

---

## 11. Background Compression Service
**File:** `VideoCompressService.java`

- Foreground service (stays alive when app backgrounded)
- Job queue — multiple videos processed sequentially
- Per-job progress callbacks
- Cancel individual jobs or entire queue
- Auto-stop when queue empty
- Binds to Activity for UI updates

**Register in manifest:**
```xml
<service android:name=".services.VideoCompressService"
    android:foregroundServiceType="dataSync"/>
```

---

## 12. Multi-Video Gallery Select
**File:** `VideoPickerHelper.java`

```java
videoPicker.openGalleryMulti(); // select up to 10 videos
```

Callback:
```java
new VideoPickerHelper(this,
    uri  -> handleSingle(uri),
    uris -> handleMultiple(uris));
```

---

## 13. Extended Format Support
**File:** `VideoPickerHelper.java`

`openFilePicker()` opens Android's Files app with MIME types:
- `video/mp4`, `video/quicktime` (MOV), `video/x-matroska` (MKV)
- `video/x-msvideo` (AVI), `video/webm`, `video/3gpp`

---

## 14. Compression Stats + Analytics
**File:** `VideoCompressionStats.java`, `VideoQualityPreferences.java`

Tracks:
- Total videos compressed, total MB saved
- Average savings ratio
- Best single-video savings
- Codec breakdown (H.264 / HEVC / AV1 %)
- Optional Firebase sync for cross-device stats

```java
VideoCompressionStats stats = new VideoCompressionStats(ctx);
stats.record(result);
tvStats.setText(stats.getSummary());
```

---

## 15. Compression Savings Toast
**File:** `VideoCompressionStats.java` → `formatResult()`

Shows after each compress: `"2.1 MB → 0.7 MB  saved 67%"`

---

## Files Changed / Added

| File | Status | Description |
|---|---|---|
| `utils/VideoCompressor.java` | UPDATED | Multi-codec, quality levels, adaptive bitrate, HW check |
| `utils/VideoPickerHelper.java` | UPDATED | Multi-select, file picker, format support, warn long videos |
| `utils/VideoUploader.java` | UPDATED | Chunked upload, pause/resume/cancel, stats callback |
| `utils/MediaCompressor.java` | UPDATED | Quality-aware, WebP image support |
| `utils/NetworkUtils.java` | UPDATED | Added `isWifi()` method |
| `utils/VideoQualityPreferences.java` | NEW | Quality settings, per-chat memory, stats |
| `utils/VideoCompressionStats.java` | NEW | Analytics, codec breakdown, Firebase sync |
| `ui/VideoQualitySheet.java` | NEW | Quality picker bottom sheet |
| `activities/VideoTrimActivity.java` | NEW | In-app video trim UI |
| `services/VideoCompressService.java` | NEW | Background foreground service + job queue |
| `workers/VideoUploadWorker.java` | UPDATED | Progress notification, quality support, chunked upload |
| `res/layout/bottom_sheet_video_quality.xml` | NEW | Quality sheet layout |
| `res/layout/activity_video_trim.xml` | NEW | Trim activity layout |
| `AndroidManifest_VIDEO_ADDITIONS.xml` | NEW | Manifest additions + integration guide |

---

## Migration from v23

1. Add manifest entries (see `AndroidManifest_VIDEO_ADDITIONS.xml`)
2. `VideoCompressor.compress(ctx, uri, callback)` — same API, now uses STANDARD quality
3. `VideoCompressor.compress(ctx, uri, Quality.HD, callback)` — new quality overload
4. `VideoUploadWorker.enqueue(ctx, path, chatId, msgId, isGroup)` — same API
5. `VideoUploader.upload()` now returns `VideoUploader` instance (was void)
6. `UploadCallback.onSuccess()` — 2 new params: `compressionSummary`, `savingsPercent`

No database migration needed.
