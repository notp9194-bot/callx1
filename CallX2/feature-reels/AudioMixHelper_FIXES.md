# AudioMixHelper — Bug Fixes & Upgrade Notes (v2)

All 13 issues identified in code review have been fixed.

## Critical Fixes (Crash / Silent Failure)

### FIX 1 — Stereo→Mono Downmix
**Was:** `outBuf.asShortBuffer()` read raw interleaved L+R samples as if mono → pitch shift / distortion.  
**Now:** After decode, channels are averaged per frame to produce proper mono PCM.

### FIX 2 — Sample Rate Resampling
**Was:** Music (e.g. 48000 Hz) mixed directly with mic audio (e.g. 44100 Hz) → out-of-sync chipmunk effect.  
**Now:** `resample()` uses linear interpolation to convert any source rate to the mic's target rate before mixing.

### FIX 3 — OOM on Long Videos (ArrayList<Short> boxing removed)
**Was:** `ArrayList<Short>` boxed every sample → ~40 MB heap overhead for 60s video → OOM on low-end phones.  
**Now:** Primitive `short[]` with manual growth (doubling strategy). Zero boxing, 8× lower memory.

### FIX 4 — Network on Main Thread
**Was:** `downloadToCache()` used `HttpURLConnection` — if called from main thread → `NetworkOnMainThreadException`.  
**Now:** `mixAndExport()` always runs on the `EXECUTOR` thread pool. Main thread is never blocked.

## Serious Logic Fixes

### FIX 5 — SHA-256 Cache Key (no hash collision)
**Was:** `urlStr.hashCode()` → 32-bit int → possible collision, wrong music served from cache.  
**Now:** `sha256(urlStr)` → 64-char hex key. Probability of collision: astronomically zero.

### FIX 6 — AAC_ADTS Voiceover Decode
**Was:** `MediaRecorder` saves voiceover as raw AAC_ADTS. `MediaExtractor` cannot find an audio track in raw ADTS → voiceoverPcm silently empty.  
**Now:** `wrapAdtsIfNeeded()` detects ADTS magic bytes (0xFFF sync word) and re-muxes into an M4A container before decoding.

### FIX 7 — Soft Limiter (no hard-clip distortion)
**Was:** Loud mix paths hard-clipped at `Short.MAX_VALUE` → digital distortion / square wave artifacts.  
**Now:** Cubic saturation curve above ±24000 — loud peaks are compressed smoothly, never squared off.

### FIX 8 — Interleaved Mux
**Was:** All video samples written first, then all audio samples → non-interleaved MP4 → sync drift or invalid file in strict players.  
**Now:** `muxInterleaved()` uses a min-PTS selector loop — video and audio samples are interleaved by presentation timestamp.

## Feature Additions

### FIX 9 — Music Start Offset (`musicStartMs`)
**Was:** Music always started from 0:00 — user couldn't choose the chorus or a specific section.  
**Now:** `mixAndExport()` accepts `musicStartMs` (long, milliseconds). Propagated through:  
- `ReelAudioMixerActivity` (`EXTRA_MUSIC_START_MS` in, `RESULT_MUSIC_START_MS` out)  
- `ReelEditorActivity` (pass-through via intent)  
- `ReelUploadActivity` (reads `mix_music_start_ms`, passes to `mixAndExport`)

### FIX 10 — Real Progress Reporting
**Was:** Hardcoded jumps (5→20→40→55…) regardless of actual processing time.  
**Now:** AAC encoder reports frame-level progress (framesDone/totalFrames × 100); other steps report at their natural completion points.

### FIX 11 — Temp File Cleanup on Error
**Was:** `aacTemp.delete()` only ran on the success path — crashes left temp files in cache forever.  
**Now:** A `temps` list collects all temp files; a `finally` block deletes them on every exit path (success, error, cancellation).

### FIX 12 — Per-Call Cancellation Support
**Was:** Shared static `ExecutorService` with no way to cancel an in-flight mix.  
**Now:** `mixAndExport()` returns a `MixHandle`. Call `handle.cancel()` to stop the mix immediately. The callback is silently suppressed after cancellation. `EXECUTOR` is a cached thread pool — supports concurrent mixes.

### FIX 13 — Download Retry Logic
**Was:** Single `HttpURLConnection` attempt — any transient failure → permanent error toast.  
**Now:** Up to 3 attempts with exponential back-off (500 ms, 1000 ms). Each attempt checks for cancellation. `User-Agent` header added.

## API Changes

```java
// Old signature (still works via convenience overload)
AudioMixHelper.mixAndExport(ctx, videoPath, musicUrl, voiceoverPath,
        micVol, musicVol, voiceoverVol, callback);

// New full signature
MixHandle handle = AudioMixHelper.mixAndExport(ctx, videoPath, musicUrl,
        musicStartMs,   // ← NEW: ms offset into music track
        voiceoverPath, micVol, musicVol, voiceoverVol, callback);

// Cancel anytime
handle.cancel();
```
