# CallX — Duet System Gap Fixes v7 (6 Critical Bugs Fixed)

## Summary of Fixes

| # | File Changed | Gap Fixed |
|---|---|---|
| GAP #1 | `ReelRemixSettingsActivity.java` | RemixSettings saved to wrong Firebase path |
| GAP #2 | `StitchReelActivity.java` + `ReelUploadActivity.java` + `StitchNotificationWorker.java` (NEW) + `ReelPlayerFragment.java` | Stitch data never saved to Firebase, no notification, no stitchCount |
| GAP #3 | `DuetReelActivity.java` | Reaction Bubble mode had no live camera preview |
| GAP #4 | `ReelPlayerFragment.java` | No way to view duets from feed |
| GAP #5 | `ReelPlayerFragment.java` | `duetCount` never shown in feed |
| GAP #6 | `ReelUploadActivity.java` | `duetLayoutMode` never saved to Firebase |

---

## GAP #1 — ReelRemixSettingsActivity: Wrong Firebase Path (CRITICAL)

**Problem:** Settings were saved to `reels/{reelId}/remix_settings/duet` but
`ReelPlayerFragment` reads `reel.allowDuetLevel` which maps to `reels/{reelId}/allowDuetLevel`.
Result: changing "Who can duet" in settings had zero effect on the feed.

**Fix:** `saveClickListeners()` now writes directly to the reel node:
```java
allUpdates.put("allowDuetLevel",   duetLevel);   // ← feed reads this ✅
allUpdates.put("allowDuet",        !"off".equals(duetLevel));  // legacy bool
allUpdates.put("allowStitchLevel", stitchLevel); // ← feed reads this ✅
allUpdates.put("allowStitch",      !"off".equals(stitchLevel));
// remix/audio/show_views etc still go to remix_settings sub-node
allUpdates.put("remix_settings/remix", ...);
```

`loadCurrentSettings()` also fixed to read from the correct top-level reel fields.

---

## GAP #2 — Stitch: Metadata Never Saved, No Count, No Notification (CRITICAL)

**Problem:** When a stitch was published:
- `stitchOf`, `stitchOfOwnerUid` were never written to the new reel's Firebase node
- `stitchCount` on the original reel was never incremented
- Original creator received zero notifications
- `StitchReelActivity` didn't even pass the original reel ID through the editor chain

**Fix — StitchReelActivity.java:**
- Added `EXTRA_ORIGINAL_OWNER_UID` extra constant
- Reads `originalReelId` and `originalOwnerUid` from intent
- `launchEditor()` passes both through to `ReelEditorActivity` → `ReelUploadActivity`

**Fix — ReelPlayerFragment.java (openStitch):**
```java
i.putExtra(StitchReelActivity.EXTRA_ORIGINAL_OWNER_UID, reel.uid); // ✅ NEW
```

**Fix — ReelUploadActivity.java:**
- Reads `isStitch`, `stitchOriginalId`, `stitchOwnerUid`, `stitchDurationSec` from intent
- In `saveReelToFirebase()`:
  ```java
  reel.stitchOf         = stitchOriginalId;
  reel.stitchOfOwnerUid = stitchOwnerUid;
  ```
- After reel published:
  ```java
  FirebaseUtils.getReelsRef().child(stitchOriginalId)
      .child("stitchCount").setValue(ServerValue.increment(1));
  StitchNotificationWorker.enqueue(...);
  ```

**New File — StitchNotificationWorker.java:**
- Exact mirror of `DuetNotificationWorker` for stitch type
- Step 1: FCM push via `PushNotify.notifyReelStitch()`
- Step 2: In-app notification → `reel_notifications/{ownerUid}/`
- Step 3: Queue fallback → `reelNotifQueue/{ownerUid}/stitches/`

---

## GAP #3 — DuetReelActivity: Reaction Bubble No Live Camera (UX BUG)

**Problem:** In `LAYOUT_REACTION_BUBBLE` mode, `previewViewCamera` was set to `View.GONE`.
The bubble overlay was just a plain `View` — user couldn't see their own camera feed while recording.
Compositing worked post-recording, but the live UX was broken.

**Fix — DuetReelActivity.java (applyLayoutToViews):**
```java
case LAYOUT_REACTION_BUBBLE:
    int bubbleDp = (int)(110 * getResources().getDisplayMetrics().density);
    lp2.width  = bubbleDp;
    lp2.height = bubbleDp;
    previewViewCamera.setVisibility(View.VISIBLE);  // ✅ live camera now visible
    setupBubbleDrag();
    break;
```

**Fix — setupBubbleDrag():**
```java
// Default position: sync camera preview to bubble
previewViewCamera.setX(defaultX);
previewViewCamera.setY(defaultY);

// On drag: move camera preview with the bubble
if (previewViewCamera != null && previewViewCamera.getVisibility() == View.VISIBLE) {
    previewViewCamera.setX(nx);
    previewViewCamera.setY(ny);
}
```

---

## GAP #4 & #5 — Feed: No Duet Count Display, No "View Duets" Entry Point

**Problem:** `duetCount` was tracked in Firebase and stored in `ReelModel` but never shown.
Users had no way to browse duets of a reel from the feed — `DuetsByReelActivity` was only
reachable from inside `DuetReelActivity` (during active recording).

**Fix — ReelPlayerFragment.java (populateStaticData + addViewDuetsButton):**
```java
// In populateStaticData():
if (reel.duetCount > 0 && (reel.duetOf == null || reel.duetOf.isEmpty())) {
    addViewDuetsButton();  // shown only on original reels, not on duets
}

// addViewDuetsButton() creates a pill-shaped chip programmatically:
// "🔀 3 Duets ›"  → opens DuetsByReelActivity on tap
```

No XML change required — the chip is injected at position 0 of `containerHashtags`.

---

## GAP #6 — DuetLayoutMode Never Saved to Firebase

**Problem:** `DuetReelActivity.openEditor()` passed `"duet_layout_mode"` extra to `ReelEditorActivity`,
but `ReelUploadActivity.handleEditorExtras()` never read this extra.
Result: `ReelModel.duetLayoutMode` was always `0` in Firebase regardless of layout chosen.

**Fix — ReelUploadActivity.java:**
```java
// handleEditorExtras():
duetLayoutMode = i.getIntExtra("duet_layout_mode", 0);

// saveReelToFirebase() → when building ReelModel:
reel.duetLayoutMode = a.duetLayoutMode;  // ✅ now saved
```

---

## Files Changed in v7

| File | Type |
|---|---|
| `feature-reels/.../settings/ReelRemixSettingsActivity.java` | Modified |
| `feature-reels/.../social/StitchReelActivity.java` | Modified |
| `feature-reels/.../upload/ReelUploadActivity.java` | Modified |
| `feature-reels/.../feed/ReelPlayerFragment.java` | Modified |
| `feature-reels/.../social/DuetReelActivity.java` | Modified |
| `feature-reels/.../workers/StitchNotificationWorker.java` | **NEW** |
| `UPGRADE_NOTES_v7_GapFixes.md` | **NEW** |

---

## Server-Side Required

For `StitchNotificationWorker` to send FCM push notifications, your server must handle:
```
POST /notify/reel  { type: "stitch", ownerUid, fromUid, fromName, fromPhoto, reelId, thumb }
```

If your server already handles the duet type (from v6), add a `"stitch"` case:
```javascript
case "stitch":
  message = `${fromName} stitched your reel ✂️`;
  notifType = "TYPE_STITCH";  // handled by ReelFCMNotificationHandler
  break;
```
