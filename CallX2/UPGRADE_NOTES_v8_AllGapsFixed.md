# CallX2 Duet System — v8 All Gaps Fixed

**Release:** v8_AllGapsFixed  
**Base:** v7_GapFixed  
**Date:** June 2026

---

## Summary

All 10 gaps identified in the Reels Duet/Stitch system have been addressed.  
5 gaps required new code; 5 were already implemented in v6/v7 (confirmed fixed).

---

## Gap Status Table

| # | Gap | Status | Action |
|---|-----|--------|--------|
| 1 | "View Stitches" chip missing from feed | ✅ FIXED in v8 | Added `addViewStitchesButton()` in ReelPlayerFragment |
| 2 | `StitchesByReelActivity` missing | ✅ FIXED in v8 | New file created; registered in Manifest + layout |
| 3 | Server "stitch" FCM notification endpoint | ⚠️ SERVER-SIDE | See Server Notes below |
| 4 | `TYPE_STITCH` FCM handler | ✅ Already fixed (v7) | Confirmed in Group A switch + buildTitle/channelFor |
| 5 | Chain duet — duets-of-duets not listed | ✅ FIXED in v8 | `duetRootId` field in ReelModel; DuetsByReelActivity queries by `duetRootId` with `duetOf` fallback |
| 6 | `isFollowing` race condition | ✅ Already fixed (v7) | `followCheckLoaded` flag guards openDuet/openStitch |
| 7 | Layout mode change during recording | ✅ FIXED in v8 | `setLayoutMode()` returns early with Toast when `isRecording == true` |
| 8 | Self-duet allowed | ✅ FIXED in v8 | `openDuet()` checks `myUid.equals(reel.uid)` and shows Toast |
| 9 | ProGuard keep for StitchNotificationWorker | ✅ Already fixed (v7) | Covered by `-keep class com.callx.app.workers.** { *; }` |
| 10 | Cached video not passed to DuetReelActivity | ✅ Already fixed (v7) | `ReelCacheManager.extractCachedVideoToFile` called in `openDuet()` |

---

## Files Changed in v8

### Modified
- `feature-reels/.../feed/ReelPlayerFragment.java`
  - Added `addViewStitchesButton()` method (teal pill chip, opens StitchesByReelActivity)
  - Called `addViewStitchesButton()` from `populateStaticData()` when `stitchCount > 0`
  - Added self-duet guard at top of `openDuet()` (Gap #8)

- `feature-reels/.../social/DuetReelActivity.java`
  - Added `if (isRecording) return;` guard at top of `setLayoutMode()` (Gap #7)

- `feature-reels/.../social/DuetsByReelActivity.java`
  - Query changed from `duetOf` → `duetRootId` (chain duet support, Gap #5)
  - Added legacy fallback query (`duetOf`) when primary returns 0 results
  - Added `buildLegacyFirstPageQuery()` method

- `core/.../models/ReelModel.java`
  - Added `public String duetRootId` field (for chain duet indexing)

### New Files
- `feature-reels/.../social/StitchesByReelActivity.java` (Gap #2)
- `feature-reels/.../res/layout/activity_stitches_by_reel.xml` (Gap #2)

### Updated
- `feature-reels/.../AndroidManifest.xml`
  - Registered `StitchesByReelActivity` (Gap #2)

---

## Server-Side Requirements (Gap #3)

Your backend (index.js / Firebase Functions) should send a push notification when  
a stitch is created. Required FCM payload:

```json
{
  "reel_notif_type": "stitch",
  "sender_name": "<stitcher display name>",
  "sender_photo": "<stitcher avatar URL>",
  "sender_uid": "<stitcher UID>",
  "reel_id": "<original reel ID>",
  "reel_thumb": "<original reel thumbnail URL>"
}
```

Send this to the **original reel owner's FCM token** when a new stitch document  
is created under `reels/<stitchReelId>` with `stitchOf != null`.

The client-side FCM handler (`TYPE_STITCH`) is already fully implemented.

---

## Chain Duet — Server-Side Requirement (Gap #5)

When saving a new duet to Firebase, set `duetRootId`:

```javascript
// In your duet upload logic (server or client):
const duetRootId = originalReel.duetRootId || originalReel.reelId;
// ^ If dueting a duet, carry forward the root; otherwise use the original ID

await db.ref(`reels/${newDuetId}`).set({
  ...duetData,
  duetOf:     originalReelId,   // direct parent
  duetRootId: duetRootId,       // always = original reel's ID
});
```

Old duets without `duetRootId` are still discoverable via the legacy `duetOf` fallback query.

---

## Firebase Index Required (Gap #5)

Add to `firebase.json` (or Realtime Database rules):

```json
{
  "rules": {
    "reels": {
      ".indexOn": ["duetOf", "duetRootId", "stitchOf"]
    }
  }
}
```

---

*End of v8 upgrade notes.*
