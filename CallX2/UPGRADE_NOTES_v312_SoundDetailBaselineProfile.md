# v312 — SoundDetail: baseline-profile + macrobenchmark coverage (gap #6)

## Root cause
`macrobenchmark/` already existed with a real generator
(`CallXBaselineProfileGenerator.kt`, 9 journeys) and per-flow jank tests
(`ChatScrollBenchmark.kt`, `ChatMediaBenchmark.kt`, etc.), but every single
journey was chat/status/calls/groups. Sound Detail — reached off literally
every reel's music row (`tv_music_name` / `ivMusicDisc` →
`delegate.openSoundDetail()`, see `ReelUiController`) — had none. Concretely
that meant:

- `app/src/main/baseline-prof.txt` had zero entries for
  `SoundDetailFragment`/`SoundDetailActivity`, so `bindViews()`'s ~48 view
  lookups, `applySoundsNodeEntry()`, the reel-grid DiffUtil/adapter setup,
  and `SoundWaveformView`'s draw path all ran JIT-only on a cold open —
  no AOT compilation — however well `SoundDetailCache` cached the
  underlying Firebase reads.
- No jank regression guard existed for the reel grid inside Sound Detail
  either (unlike the chat list/thread, which `ChatScrollBenchmark.kt`
  covers).

## Fix

**`CallXBaselineProfileGenerator.kt`** — added a 10th journey,
`generateSoundDetailFlow()`: Reels tab open (`nav_reels` → `vp_reels`) →
tap a reel's music row (`tv_music_name`) → Sound Detail opens
(`rv_sound_reels` populated) → scroll the grid (`scroll_sound_detail`,
the outer `NestedScrollView` — the grid doesn't scroll independently, see
`SoundDetailFragment`'s `rvReels.setNestedScrollingEnabled(true)` doc).
Running `./gradlew :macrobenchmark:generateBaselineProfile` now AOT-warms
this path same as the other 9.

**`SoundDetailBenchmark.kt`** (new) — mirrors `ChatScrollBenchmark.kt`'s
pattern:
- `soundDetailColdOpenWithProfile` — `StartupTimingMetric` +
  `FrameTimingMetric`, cold start straight through to a populated grid.
- `soundDetailGridScrollWithProfile` — grid scroll jank,
  `CompilationMode.Partial()`.
- `soundDetailGridScrollNoCompilation` — same scroll,
  `CompilationMode.None()`, as the baseline to diff the profile's P99
  improvement against.

## Not done here (deliberately)
`macrobenchmark/baselines/jank_baseline.json` is captured device data
(Pixel 6 API 31 GMD), regenerated via
`node macrobenchmark/scripts/check_jank_regression.js --update-baseline
<resultsDir>` — its own header says never hand-edit the numbers. No entries
were added for `soundDetail*` here; run the new benchmarks on a real
device/GMD and use that script to add them once real numbers exist.

## Next step (manual, on a connected device/emulator)
```
./gradlew :macrobenchmark:generateBaselineProfile
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.callx.benchmark.SoundDetailBenchmark
```

## Files touched
- `macrobenchmark/src/main/java/com/callx/benchmark/CallXBaselineProfileGenerator.kt`
- `macrobenchmark/src/main/java/com/callx/benchmark/SoundDetailBenchmark.kt` (new)

---

# Build fix — ReelFirebaseUtils module-boundary break

`:core:compileDebugJavaWithJavac` failed:
```
core/src/main/java/com/callx/app/cache/SoundDetailCache.java:7: error: cannot find symbol
import com.callx.app.utils.ReelFirebaseUtils;
symbol:   class ReelFirebaseUtils
```
`ReelFirebaseUtils` physically lived in `:feature-reels`, but `SoundDetailCache`
(added in v300, for the reelUsers/ → users/ creator-profile fallback) is in
`:core`. `:core` cannot depend on `:feature-reels` — only the reverse — so it
was never on core's compile classpath; feature-reels itself never noticed
because it obviously always had itself on its own classpath.

**Fix:** moved `ReelFirebaseUtils.java` from `feature-reels/.../utils/` to
`core/.../utils/` (same package, so every existing
`import com.callx.app.utils.ReelFirebaseUtils;` in feature-reels/feature-chat/
core keeps resolving unchanged — both those modules already declare
`implementation project(':core')`). No other code changes needed; the class
only ever depended on `Constants` (already in core) and the Firebase SDK
(already a `:core` dependency via `FirebaseUtils`).

Files touched: `core/src/main/java/com/callx/app/utils/ReelFirebaseUtils.java` (moved here), `feature-reels/src/main/java/com/callx/app/utils/ReelFirebaseUtils.java` (removed).
