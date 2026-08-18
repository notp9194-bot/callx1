# Macrobenchmark + Dex Layout Optimization — CallX2

## Kya kiya gaya hai (Summary)

Is update mein 2 advanced chat performance techniques add ki gayi hain:

### 1. Real Macrobenchmark Module (`:macrobenchmark`)
Hand-written baseline profile ki jagah **real device pe measured** profile generate karta hai.

### 2. Dex Layout Optimization
Hot chat classes ko **primary dex** mein force karta hai — class loader startup I/O kam hota hai.

---

## Naye Files

| File | Kya karta hai |
|------|--------------|
| `macrobenchmark/build.gradle` | Benchmark module Gradle config |
| `macrobenchmark/src/main/AndroidManifest.xml` | Module manifest |
| `macrobenchmark/src/.../CallXBaselineProfileGenerator.kt` | **Main file** — real flows record karta hai |
| `macrobenchmark/src/.../ChatStartupBenchmark.kt` | Cold/warm/hot startup timing |
| `macrobenchmark/src/.../ChatScrollBenchmark.kt` | Frame timing — scroll jank measurement |
| `app/src/main/multidex-config.pro` | Primary dex hot class keep rules |
| `app/benchmark-rules.pro` | Benchmark build type ProGuard |

## Modified Files

| File | Change |
|------|--------|
| `app/build.gradle` | `benchmark` build type + `multiDexKeepProguard` + `dexLayoutOptimization` + `baselineProfile {}` block |
| `settings.gradle` | `:macrobenchmark` module include |
| `build.gradle` | Kotlin + `androidx.baselineprofile` plugin version declare |
| `app/src/main/baseline-prof.txt` | v2 — 3× more coverage (all controllers, UI, gesture, group, media) |

---

## Ek Baar Setup (First Time)

### Step 1 — AGP + Kotlin plugin sync
```
File → Sync Project with Gradle Files
```

### Step 2 — Real device ya emulator connect karo
- **Real device recommended**: Pixel 6 / 7 ya koi bhi ARM64 device
- API 28+ required (Macrobenchmark constraint)
- Google Play enabled hona chahiye (baseline profile install ke liye)
- USB debugging ON, developer options mein "Stay awake" ON

### Step 3 — Baseline Profile Generate Karo
```bash
./gradlew :macrobenchmark:generateBaselineProfile
```
Ye command:
1. App install karega `:app:assembleBenchmark`
2. 4 user journeys × 3 iterations real device pe run karega
3. ART traces collect karega
4. `app/src/main/baseline-prof.txt` update karega measured data se

> ⏱ Time: ~15-25 min first time (3 iterations × 4 journeys × app install)

### Step 4 — Build + Ship
```bash
./gradlew :app:assembleRelease
```
Generated profile automatically APK mein bundle hoga.

---

## Performance Measurement

Before/after comparison karo:

```bash
# Startup timing (no profile vs with profile)
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  com.callx.benchmark.ChatStartupBenchmark

# Scroll frame timing (jank measurement)
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  com.callx.benchmark.ChatScrollBenchmark
```

### Expected Improvements (real device, ARM64)

| Metric | Before (JIT only) | After (Baseline Profile) | Gain |
|--------|-------------------|--------------------------|------|
| Cold start → chat list | ~900-1400ms | ~600-900ms | ~25-35% |
| Chat open (TTFD) | ~600-1000ms | ~400-650ms | ~30-40% |
| First scroll P99 frame | ~28-45ms | ~16-22ms | ~40-50% |
| Dex class load (primary) | Secondary I/O | Primary dex | ~30-80ms |

---

## Dex Layout Optimization — Details

`app/src/main/multidex-config.pro`:
- 60+ hot classes listed — startup + chat path
- Primary dex mein force hoti hain
- Class loader inhe secondary dex se load nahi karta

`experimentalProperties` in `app/build.gradle`:
```groovy
experimentalProperties["android.experimental.art-profile-r8-rewriting"] = true
experimentalProperties["android.experimental.r8.dex-startup-optimization"] = true
```
Ye R8 ko baseline profile se dex layout reorder karne deta hai — hot methods physically adjacent pages mein aate hain.

---

## RecyclerView Resource IDs (Important!)

`CallXBaselineProfileGenerator.kt` mein ye resource IDs use ho rahi hain:
```kotlin
By.res(TARGET_PACKAGE, "recyclerChats")     // ChatsFragment RecyclerView
By.res(TARGET_PACKAGE, "recyclerMessages")  // ChatActivity RecyclerView
By.res(TARGET_PACKAGE, "inputMessage")      // Chat EditText
By.res(TARGET_PACKAGE, "btnSend")           // Send button
By.res(TARGET_PACKAGE, "tabGroups")         // Groups tab
By.res(TARGET_PACKAGE, "recyclerGroups")    // Groups RecyclerView
```

**Agar IDs different hain**, to `CallXBaselineProfileGenerator.kt` mein update karo. Profile generator
gracefully handle karta hai missing views (null check + return).

---

## Maintenance

- **Naya feature add kiya** → `multidex-config.pro` mein hot classes add karo
- **New Activity important hai** → `CallXBaselineProfileGenerator.kt` mein journey add karo
- **Profile stale ho gayi** (3-6 months baad) → `generateBaselineProfile` dobara run karo
- **CI mein automate karna** → Monthly scheduled job banao jo profile regenerate kare
