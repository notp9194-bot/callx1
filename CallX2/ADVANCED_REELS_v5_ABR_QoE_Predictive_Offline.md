# Advanced Reels — v5: ABR Engine, QoE Analytics, Predictive Preloader, Offline Manager

## 4 New Production-Grade Systems

---

## 1. ReelABREngine — MPC-like Segment-level ABR

**File:** `feature-reels/src/main/java/com/callx/app/player/ReelABREngine.java`

### What it does
Replaces the basic EWMA in `AdaptiveStreamingManager` with a proper
Model Predictive Control (MPC) ABR algorithm — the same approach used
by Netflix and Pensieve.

### Key upgrades vs old AdaptiveStreamingManager
| Feature | Old (AdaptiveStreamingManager) | New (ReelABREngine) |
|---|---|---|
| BW estimation | EWMA (arithmetic) | Harmonic mean (closer to MPC lit) |
| Decision trigger | Timer / state change | Every ~2s segment tick |
| Buffer awareness | No | Full buffer-level gating |
| MPC lookahead | None | 5-segment QoE prediction |
| Emergency downgrade | No | Immediate on <1.2s buffer |
| Oscillation damping | No | Hold time: 4s up, 1.5s down |
| Rebuffering penalty | No | QoE weight = 3000 per second |

### How to use
```java
// 1. Build player with AdaptiveStreamingManager as before
ExoPlayer player = AdaptiveStreamingManager.get(ctx).buildPlayer(url, cap, null);

// 2. Attach ReelABREngine for smarter real-time decisions
ReelABREngine.ABRSession session = ReelABREngine.get(ctx).attachTo(
    player, trackSelector,
    new ReelABREngine.ABRDecisionListener() {
        @Override
        public void onABRDecision(long prev, long next, long bufMs,
                                  long bwKbps, boolean isDown, boolean isEmergency) {
            // Log to ReelQoEAnalyticsActivity or update debug overlay
        }
        @Override public void onStallBegin() { /* show buffering spinner */ }
        @Override public void onStallEnd(long ms) { /* hide spinner, log stall */ }
    }
);

// 3. On player release:
ReelABREngine.get(ctx).detach(session);
player.release();
```

---

## 2. ReelQoEAnalyticsActivity — QoE Dashboard + Firebase Persistence

**Files:**
- `feature-reels/src/main/java/com/callx/app/analytics/ReelQoEAnalyticsActivity.java`
- `feature-reels/src/main/res/layout/activity_reel_qoe_analytics.xml`

### What it shows
- Total sessions, avg stall per video, avg TTFF
- Quality switch breakdown (upgrades ↑ / downgrades ↓)
- Stall rate progress bar
- Firebase sync button + auto-sync (1h interval)

### Firebase schema
```
qoe_analytics/
  {uid}/
    sessions/
      {timestamp}/         ← per-sync aggregate
        sessions: 42
        total_stall_ms: 12000
        avg_stall_ms: 285
        total_switches: 18
        upgrades: 11
        downgrades: 7
        avg_ttff_ms: 380
        device_model: "Pixel 7"
        os_sdk: 34
        timestamp: 1719456000000
        network_type: "WIFI"
    reel_sessions/
      {reelId}_{timestamp}/  ← per-reel-play session
        reel_id: "abc123"
        stall_ms: 400
        ttff_ms: 320
        switches: 2
        avg_bitrate_kbps: 1800
    lifetime/               ← transaction-merged aggregate
        sessions: 42
        total_stall_ms: 12000
        avg_stall_ms: 285
        total_switches: 18
        last_updated: 1719456000000
```

### Firebase security rules
Add to your `database.rules.json`:
```json
"qoe_analytics": {
  "$uid": {
    ".read": "$uid === auth.uid",
    ".write": "$uid === auth.uid"
  }
}
```

### Push a single reel session from anywhere
```java
ReelQoEAnalyticsActivity.pushSessionToFirebase(
    context, reelId,
    stallMs, ttffMs, qualitySwitches, avgBitrateKbps
);
```

### Open the dashboard
```java
startActivity(new Intent(context, ReelQoEAnalyticsActivity.class));
```

---

## 3. ReelPredictivePreloader — Watch-pattern Learning

**File:** `feature-reels/src/main/java/com/callx/app/cache/ReelPredictivePreloader.java`

### What it does
Upgrades the naive "preload N+1, N+2, N+3" with a learned watch-pattern model:

1. Builds a **Markov transition matrix**: P(category A → category B)
2. Scores each upcoming reel by `affinity × transition_prob × position_decay`
3. Preloads in **priority order** — high-probability reels get bandwidth first
4. Learns user's **skip threshold** (EWMA): if user skips after 2s, low-score reels get fewer bytes

### How to integrate (replaces ReelVideoPreloader)
```java
// In ReelsFragment:
private ReelPredictivePreloader preloader;

@Override
public void onCreateView(...) {
    preloader = new ReelPredictivePreloader(requireContext());
}

// On each page change (record + preload):
@Override
public void onPageSelected(int position) {
    // Record watch event for previous reel
    if (currentReel != null) {
        preloader.recordWatch(currentReel, watchedMs, totalDurationMs);
    }
    currentReel = reelList.get(position);

    // Smart preload based on learned model
    preloader.preloadSmartFrom(reelList, position);
}

@Override
public void onDestroyView() {
    preloader.shutdown();
}
```

### Model persistence
- Stored in `SharedPreferences("reel_watch_model")`
- Survives app restarts
- `transitionMatrix` JSON: category → {nextCategory → count}
- `categoryAffinity` JSON: category → watchCount
- Learned skip threshold (float) updated via EWMA

### Debug
```java
Log.d("Model", preloader.dumpModel());
// Output:
// Skip threshold: 28%
// Category affinities (top 5):
//   dance → 23
//   comedy → 18
//   music_blinding_lights → 11
//   fitness → 9
//   general → 7
// Transition matrix: 12 source categories
```

---

## 4. ReelOfflineManager — Cache Fallback + Retry

**File:** `feature-reels/src/main/java/com/callx/app/player/ReelOfflineManager.java`

### What it does
- **Offline detection**: hooks into NetworkQualityMonitor
- **Cache fallback**: serves from ExoPlayer's CacheDataSource transparently
- **Degraded mode**: auto-selects 480p URL on 2G/poor signal
- **Retry with exponential backoff**: 1s → 4s → 16s (3 attempts)
- **Download for offline**: explicitly cache a reel (50 MB) for offline playback
- **Offline catalog**: tracks which reels are saved offline (SharedPreferences)
- **Auto-retry on network restore**: cancels pending timers, retries immediately

### States
```
ONLINE          → normal
DEGRADED        → 2G / poor signal, low-quality mode
OFFLINE         → no network
CACHE_PLAYBACK  → serving from local cache
ERROR_NO_CACHE  → offline + nothing cached → show error UI
```

### How to integrate
```java
// Application.onCreate() or ReelsFragment.onAttach():
ReelOfflineManager.get(context).setStateListener(new ReelOfflineManager.OfflineStateListener() {
    @Override
    public void onOfflineStateChanged(ReelOfflineManager.OfflineState state) {
        // Show/hide offline banner based on state
        String label = ReelOfflineManager.stateLabel(state);
        offlineBanner.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
        offlineBanner.setText(label);
    }
    @Override
    public void onRetryScheduled(String reelId, int attempt, long delayMs) {
        Log.d("Offline", "Retry #" + attempt + " in " + delayMs + "ms for " + reelId);
    }
    @Override
    public void onOfflineDownloadComplete(String reelId) {
        Toast.makeText(ctx, "Saved for offline", Toast.LENGTH_SHORT).show();
    }
    @Override
    public void onOfflineDownloadFailed(String reelId, String reason) {
        Toast.makeText(ctx, "Download failed: " + reason, Toast.LENGTH_SHORT).show();
    }
});

// Resolve MediaSource (replaces your current buildSource() calls):
MediaSource source = ReelOfflineManager.get(context).resolveSource(reel, player);
if (source != null) {
    player.setMediaSource(source);
    player.prepare();
} else {
    // State is ERROR_NO_CACHE — show "not available offline" error
}

// On playback error:
player.addListener(new Player.Listener() {
    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        ReelOfflineManager.get(context).onPlaybackError(reel, player, error);
    }
});

// Download for offline ("Save" button):
ReelOfflineManager.get(context).downloadForOffline(reel);

// List saved reels:
List<String> saved = ReelOfflineManager.get(context).getOfflineCatalog();

// Delete:
ReelOfflineManager.get(context).removeOfflineReel(reelId);
```

---

## Integration Summary — What to wire where

| Component | Wire in | Method |
|---|---|---|
| ReelABREngine | ReelPlayerController / ReelPlayerFragment | `attachTo()` after buildPlayer, `detach()` on release |
| ReelQoEAnalyticsActivity | ReelABRSettingsActivity or ReelCreatorDashboardActivity | `startActivity(new Intent(..., ReelQoEAnalyticsActivity.class))` |
| ReelQoEAnalyticsActivity.pushSession | ReelPlayerFragment.onPageSelected (prev reel) | `pushSessionToFirebase(...)` |
| ReelPredictivePreloader | ReelsFragment | Replace `ReelVideoPreloader` |
| ReelOfflineManager | ReelsFragment + ReelPlayerFragment | `resolveSource()` + `onPlaybackError()` |

## Firebase dependencies (already in build.gradle)
```
firebase-database  ✅ already added
firebase-auth      ✅ already added
```
No new dependencies needed.
