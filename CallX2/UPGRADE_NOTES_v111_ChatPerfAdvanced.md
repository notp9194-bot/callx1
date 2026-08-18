# v111 — Chat Performance Advanced (RenderNode, LongSparseArray, Bitmap Pool, CI Jank Gate)

## Kya kiya gaya hai (Summary)

4 advanced chat-performance techniques add ki gayi hain:

| # | Feature | File(s) |
|---|---------|---------|
| 1 | RenderNode/Picture caching upgrade | `canvas/MessageBubbleCanvasView.java` |
| 2 | Autoboxing kam — `LongSparseArray` caches | `conversation/MessagePagingAdapter.java` |
| 3 | Bitmap pooling (`inBitmap` reuse) | `conversation/MessageDecodeUtils.java` |
| 4 | Perfetto/Macrobenchmark CI jank gate | `macrobenchmark/**`, `.github/workflows/macrobenchmark.yml` |

---

## 1. RenderNode/Picture caching

**Note:** Full-bubble `Picture` caching (`PERF #5`) already existed in the codebase — every bubble's draw commands were recorded once into a `Picture` and replayed via `canvas.drawPicture()` until a setter/bind call marked it dirty (via the overridden `invalidate()`).

Upgrade added: on **API 29+ (Q)**, the view now uses `RenderNode` instead of `Picture` when the canvas is hardware-accelerated:
- `RenderNode`'s display list lives GPU-side; `canvas.drawRenderNode()` replays it without the CPU walking the recorded draw-op list the way `drawPicture()` does.
- Falls back to the existing `Picture` path automatically for API 23-28, or for the rare software-canvas draw (e.g. a bitmap snapshot).
- Same dirty-tracking (`fullBubbleDirty`, size-change guard) drives both paths — no new invalidation logic needed.

Net effect: far off-screen bubbles that come back into view (long fling to top, then back down) skip re-rasterizing text/paths entirely when content and size haven't changed — same win as before, cheaper replay on modern devices.

## 2. Autoboxing reduction — `LongSparseArray`

`MessagePagingAdapter`'s three timestamp-keyed caches converted from boxed-key collections to `android.util.LongSparseArray` (part of the Android SDK since API 16 — no new dependency, `minSdk 23` already covers it):

| Cache | Before | After |
|-------|--------|-------|
| `timeStringCache` | `LruCache<Long, String>` | `LongSparseArray<String>` |
| `dateLabelCache` | `LruCache<Long, String>` | `LongSparseArray<String>` |
| `sameDayCache` | `HashMap<Long, Boolean>` | `LongSparseArray<Boolean>` |

`LruCache`/`HashMap` both take `Long` keys, so every `get()`/`put()` on a scroll-heavy chat screen was autoboxing the primitive timestamp bucket into a new `Long` object (timestamps almost never land in the JVM's `[-128,127]` Long cache). `LongSparseArray` stores primitive `long`s directly in a backing array — zero boxing on the hot path.

Trade-off: `LongSparseArray` isn't a true LRU, so each cache now does a cheap `size() >= cap` check and `clear()`s wholesale before growing past its old `LruCache`/`HashMap` capacity (256 / 64 / 32 respectively). Worst case is a few extra recomputes right after a clear — never incorrect data, and in practice a visible scroll window only touches a handful of unique keys at a time so clears are rare.

## 3. Bitmap pooling (`inBitmap` reuse)

`MessageDecodeUtils.decodeWithBitmapFactory()` (the pre-API28 / ImageDecoder-failure fallback path) previously allocated a brand-new pixel buffer on every decode. Added:

- A small fixed-capacity (4 slots) thread-safe bitmap pool (`sBitmapPool`).
- `decodeWithBitmapFactory()` now estimates the post-sample-size decode dimensions, looks for a pooled bitmap with enough `allocationByteCount`, and passes it via `BitmapFactory.Options.inBitmap` (+ `inMutable = true`) so the decoder reuses that buffer instead of allocating fresh. Since API 19 the reuse rule is byte-count-based, not dimension-exact, and every pooled bitmap is always `RGB_565` (config is fixed by this decode path), so matches are straightforward.
- New public API: `MessageDecodeUtils.releaseBitmapToPool(Bitmap)` — callers (e.g. a media viewer closing, a holder swapping in a different image) can hand a no-longer-needed bitmap back to the pool. Never calling it is safe; the pool is a cache, not a correctness dependency.
- If a pooled candidate turns out incompatible (`IllegalArgumentException`), it's recycled and the decode retries without `inBitmap` — never a hard failure.

Note: `decodeAsync()` isn't wired to any call site in the current codebase yet (it's a prepared utility per its own doc comment) — the pool lives entirely inside `MessageDecodeUtils` and will benefit whichever screen ends up calling it, without needing further plumbing.

## 4. Perfetto/Macrobenchmark CI — automated jank regression gate

The project already had a `:macrobenchmark` module (`ChatScrollBenchmark.kt` with `FrameTimingMetric`) and a `compare_results.js` reporting script, but nothing wired it into CI or failed a build on regression. Added:

- **`macrobenchmark/baselines/jank_baseline.json`** — checked-in P50/P90/P99 `frameDurationCpuMs`/`frameOverrunMs` numbers for the three `*WithProfile` scroll/fling benchmarks. Update this deliberately (via `--update-baseline`) after an intentional perf-affecting change — never hand-edit it.
- **`macrobenchmark/scripts/check_jank_regression.js`** — the actual CI gate. Parses the current run's benchmark JSON, computes percentiles, and compares P99 per metric against the baseline with a configurable tolerance (default 20%, wider than a real-device baseline since CI runs on an emulator-backed Gradle Managed Device). Exits `1` on regression.
- **Gradle Managed Device** (`pixel6Api31`, API 31) added to `macrobenchmark/build.gradle` — `testOptions.managedDevices` — so CI can run the benchmarks headlessly on GitHub-hosted runners without a physical/USB device. Generates the task `:macrobenchmark:pixel6Api31BenchmarkAndroidTest`.
- **`.github/workflows/macrobenchmark.yml`** — runs on every PR touching `app/`, `core/`, `feature-chat/`, or `macrobenchmark/`: enables KVM, runs the GMD benchmark, prints the human-readable table (`compare_results.js`), runs the regression gate (`check_jank_regression.js`), uploads the raw JSON + Perfetto trace files as a build artifact, and fails the check on regression — blocking merge until it's addressed or the baseline is deliberately updated via `workflow_dispatch`.

### Maintenance
- **Intentional perf change lands** (e.g. this same PR) → after CI runs once, trigger the workflow manually with `update_baseline: true` to re-seed `jank_baseline.json` with the new numbers.
- **New scroll benchmark added** → add its expected P50/P90/P99 to `jank_baseline.json` (or seed via `--update-baseline`) so the gate actually covers it.
- **CI keeps flagging noise from the emulator** → bump `--tolerance-pct` in the workflow (default 20%) rather than disabling the gate.
