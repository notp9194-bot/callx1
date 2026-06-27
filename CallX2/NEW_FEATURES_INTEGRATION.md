# New Features Integration Guide

## 3 New Production Features Added

---

## 1. Adaptive Bitrate Streaming (HLS/DASH)

### New Files
- `feature-reels/.../player/AdaptiveStreamingManager.java`
- `feature-reels/.../player/NetworkQualityMonitor.java`
- `feature-reels/.../player/ReelABRSettingsActivity.java`
- `feature-reels/.../res/layout/activity_reel_abr_settings.xml`

### How to integrate

**In your ReelPlayerFragment / SingleReelPlayerActivity**, replace raw ExoPlayer creation with:

```java
// Get recommended cap based on current network
boolean isWifi = NetworkQualityMonitor.get(context).currentQuality()
    == NetworkQualityMonitor.Quality.WIFI;
AdaptiveStreamingManager.QualityCap cap =
    ReelABRSettingsActivity.getSavedCap(context, isWifi);

// Build ABR player
ExoPlayer player = AdaptiveStreamingManager.get(context)
    .buildPlayer(videoUrl, cap, new AdaptiveStreamingManager.ReelABRCallback() {
        @Override public void onQualitySelected(int w, int h, long bwKbps) {
            Log.d("ABR", "Playing at " + h + "p @ " + bwKbps + "kbps");
        }
        @Override public void onStall(int count) { /* show buffering spinner */ }
        @Override public void onPersistentStall() { /* suggest lower quality */ }
        @Override public void onError(PlaybackException e) { /* handle error */ }
    });
playerView.setPlayer(player);
player.prepare();
player.play();
```

**Start network monitoring in Application.onCreate():**
```java
NetworkQualityMonitor.get(this).startMonitoring();
```

**Add to Settings menu:**
```java
startActivity(new Intent(context, ReelABRSettingsActivity.class));
```

**Add to AndroidManifest.xml:**
```xml
<activity android:name="com.callx.app.player.ReelABRSettingsActivity"
    android:theme="@style/Theme.App"/>
```

---

## 2. Reel Remix Feature

### New Files
- `feature-reels/.../social/ReelRemixModel.java`
- `feature-reels/.../social/ReelRemixPickerSheet.java`
- `feature-reels/.../social/ReelRemixActivity.java`
- `feature-reels/.../social/RemixesByReelActivity.java`
- `feature-reels/.../res/layout/activity_reel_remix.xml`
- `feature-reels/.../res/layout/bottom_sheet_remix_picker.xml`
- `feature-reels/.../res/layout/activity_remixes_by_reel.xml`
- `feature-reels/.../res/layout/item_remix_card.xml`

### How to integrate

**Show remix picker from ReelMoreBottomSheet or feed action buttons:**
```java
ReelRemixPickerSheet.newInstance(reelModel)
    .show(getSupportFragmentManager(), "remix_picker");
```

**Show remix count button on reel (in ReelPlayerFragment):**
```java
// Add a "Remix N" button next to duet/stitch
tvRemixCount.setOnClickListener(v -> {
    Intent i = new Intent(context, RemixesByReelActivity.class);
    i.putExtra(RemixesByReelActivity.EXTRA_REEL_ID, reelModel.reelId);
    i.putExtra(RemixesByReelActivity.EXTRA_OWNER_NAME, reelModel.ownerName);
    startActivity(i);
});
```

**After editor exports the remix, publish to Firebase:**
```java
ReelRemixActivity.publishRemixToFirebase(
    originalReelId, newReelId,
    originalOwnerUid, originalOwnerName, originalThumbUrl, originalVideoUrl,
    remixerUid, remixerName, remixerPhoto,
    remixVideoUrl, remixThumbUrl, remixCaption, layoutMode);
```

**Add to AndroidManifest.xml:**
```xml
<activity android:name="com.callx.app.social.ReelRemixActivity"
    android:screenOrientation="portrait"
    android:theme="@style/Theme.App.Fullscreen"/>
<activity android:name="com.callx.app.social.RemixesByReelActivity"
    android:theme="@style/Theme.App"/>
```

**Add remixCount field to ReelModel** (already compatible — uses Firebase RTDB ServerValue.increment):
```java
public int remixCount;   // add to ReelModel.java
```

**Add to ReelPrivacySettingsActivity** (allow/disallow remixes):
```java
// Similar to allowDuet / allowStitch pattern already in ReelModel
public String allowRemixLevel = "everyone"; // "everyone" | "followers" | "off"
```

---

## 3. Watch History Management UI

### New Files
- `feature-reels/.../library/WatchHistoryItem.java`
- `feature-reels/.../library/WatchHistoryManager.java`
- `feature-reels/.../library/WatchHistoryAdapter.java`
- `feature-reels/.../library/WatchHistoryActivity.java`
- `feature-reels/.../res/layout/activity_watch_history.xml`
- `feature-reels/.../res/layout/item_watch_history.xml`
- `feature-reels/.../res/menu/menu_watch_history.xml`

### How to integrate

**Record watch when a reel starts playing (in ReelPlayerFragment or ReelSeenTracker):**
```java
WatchHistoryManager.get().record(reelModel, 0);  // 0% at start
```

**Update completion % as reel plays:**
```java
// In your player progress listener:
int percent = (int)((currentPositionMs * 100L) / totalDurationMs);
WatchHistoryManager.get().record(reelModel, percent);
```

**Open history from profile/library menu:**
```java
startActivity(new Intent(context, WatchHistoryActivity.class));
```

**Add to AndroidManifest.xml:**
```xml
<activity android:name="com.callx.app.library.WatchHistoryActivity"
    android:theme="@style/Theme.App"/>
```

---

## Firebase Security Rules

Apply rules from `firebase_new_features_rules.json` to your Firebase RTDB.

### New Firebase paths used:
- `watchHistory/{uid}/{reelId}` — User watch history
- `reelRemixes/{originalReelId}/{remixReelId}` — Remix records
- `userRemixes/{uid}/{remixReelId}` — Index: user's remixes
- `reelNotifications/{uid}/{notifId}` — Remix notifications (already used by other features)
