# CallX Duet System — All 11 Fixes

Date: June 10, 2026

---

## Fix 1 — SingleReelPlayerActivity (NEW CLASS)
**File:** `feature-reels/.../player/SingleReelPlayerActivity.java`

DuetsByReelActivity referenced this class but it didn't exist anywhere — instant crash when tapping any duet thumbnail.

- Loads reel by Firebase reelId
- Loops video with ExoPlayer
- Shows owner name + caption
- "Duet this" button (honours allowDuetLevel)
- Back button

---

## Fix 2 — Reaction Bubble Drag Touch Listener
**File:** `feature-reels/.../social/DuetReelActivity.java`

bubbleOverlay had no OnTouchListener — users could see the bubble but not move it.

- ACTION_DOWN: captures touch offset from bubble corner
- ACTION_MOVE: updates view X/Y with parent-bounds clamping
- Tracks bubbleViewX/Y for bubbleToNdc() conversion

---

## Fix 3 — bubbleToNdc() Method
**File:** `feature-reels/.../social/DuetReelActivity.java`

Method was called in onRecordingDone() but never defined — compile error.

- Converts dragged bubble VIEW position → OpenGL NDC coords
- Handles "not dragged yet" default (bottom-left area)
- Flips Y axis (GL is bottom-up, Android is top-down)

---

## Fix 4 — Original Reel Music Track in Duet Audio
**File:** `feature-reels/.../social/DuetVideoCompositor.java`
**File:** `feature-reels/.../social/DuetReelActivity.java`

If original reel had a separate music track (soundUrl in Firebase), it was never included in the duet audio mix — only embedded video audio was decoded.

- composite() now accepts separateSoundUrl parameter
- decodeAudioToRaw() decodes soundUrl into a 3rd raw PCM temp file
- preEncodeAudio() sums cam + origVideo + origMusic at individual gains
- DuetReelActivity passes EXTRA_ORIGINAL_SOUND_URL through to compositor

---

## Fix 5 — Real-time Composite Preview Overlay
**File:** `feature-reels/.../social/DuetPreviewOverlayView.java` (NEW CLASS)

During recording, users had no idea what the final composited output would look like — they only saw raw camera + original player separately.

- Custom View drawn on top of the recording layout
- Side-by-side: vertical divider line + "Original" / "You" labels
- Top-bottom: horizontal divider + labels
- PiP: small rectangle outline in corner + labels
- Reaction Bubble: circle outline at drag position + label
- Reacts to setLayoutMode() and setBubblePosition() calls in real-time

---

## Fix 6 — ReelMoreBottomSheet Duet Permission Check
**File:** Original file ALREADY CORRECT — no changes needed.

Verified: addDuetStitchItem() correctly handles "off" (hide), "followers" (gray-out if !isFollowing), "everyone" (show). Fix 6 is ✅ done.

---

## Fix 7 — ReelUploadActivity saveReelToFirebase Duet Fields
**File:** `feature-reels/.../upload/ReelUploadActivity.java`

Original file already saved duetOf, duetOfOwnerUid, duetOriginalUrl. Added:

- `chainDuetRootId` — root original reel ID for chain duets (Fix 10)
- `chainDuetDepth`  — depth counter for duet chains (Fix 10)
- `duetOriginalSoundUrl` — original reel's music URL for feed re-rendering (Fix 4)

All three are passed from DuetReelActivity → ReelEditorActivity → ReelUploadActivity via intent extras.

---

## Fix 8 — OOM Fix: Streaming Audio Decode
**File:** `feature-reels/.../social/DuetVideoCompositor.java`

Old decodeAudioToPcm() loaded ENTIRE audio into ArrayList<Short> in heap.
For 60s stereo @ 44100 Hz: 2 tracks × ~10 MB = ~20 MB on heap = OOM on low-end devices.

- Replaced ArrayList<Short> with decodeAudioToRaw() — writes PCM to temp .raw file
- Mix loop streams chunks from DataInputStream instead of reading from array
- PCM_CHUNK_SHORTS = 262144 (512 KB at a time)
- Temp .raw files deleted immediately after encode

---

## Fix 9 — Duet Watermark Text Overlay
**File:** `feature-reels/.../social/DuetVideoCompositor.java`

No watermark in output video — users couldn't tell duets from regular reels.

- "Duet with @{ownerName}" burned into top-left corner of every frame
- Drawn once to a Bitmap, uploaded as GL_TEXTURE_2D
- Dedicated watermark GL program (separate from OES video program)
- Semi-transparent black background for legibility
- Composited as final pass AFTER video frame — never occluded by video

---

## Fix 10 — Chain Duet Support (Duet of a Duet)
**File:** `feature-reels/.../social/DuetReelActivity.java`
**File:** `core/.../models/ReelModel.java`

No logic existed for dueting a reel that was itself a duet. DuetReelActivity crashed when allowDuet check ran on a duet reel node.

- DuetReelActivity accepts EXTRA_ORIGINAL_DUET_OF + EXTRA_ORIGINAL_CHAIN_DEPTH extras
- When detected: shows "Chain Duet (depth N)" banner in UI
- chainDuetRootId = original source reel's ID (never changes across chain)
- chainDuetDepth increments with each chain link
- ReelModel.isChainDuet() helper for feed rendering
- ReelUploadActivity saves chainDuetRootId + chainDuetDepth to Firebase

---

## Fix 11 — Async/Invite-to-Duet Feature
**File:** `feature-reels/.../social/DuetInviteActivity.java` (NEW CLASS)
**File:** `feature-reels/.../workers/PushNotify_DuetInvite_Patch.java` (PATCH)

No way to invite someone to duet your reel.

- DuetInviteActivity: creator-only screen accessible from DuetReelActivity
- Search followers by username (live filter, first 200 loaded)
- Tap → writes to Firebase: duetInvites/{targetUid}/{fromUid_reelId}
- Calls PushNotify.notifyDuetInvite() → in-app notification + FCM queue
- Invite button (btnInviteDuet) shown ONLY when current user = reel owner
- PushNotify_DuetInvite_Patch.java shows exact method to add to PushNotify.java

---

## Files Modified/Added

| File | Status | Fix |
|------|--------|-----|
| `player/SingleReelPlayerActivity.java` | NEW | Fix 1 |
| `social/DuetReelActivity.java` | MODIFIED v27→v28 | Fix 2, 3, 5, 10, 11 |
| `social/DuetVideoCompositor.java` | MODIFIED v5→v6 | Fix 4, 8, 9 |
| `social/DuetPreviewOverlayView.java` | NEW | Fix 5 |
| `social/DuetInviteActivity.java` | NEW | Fix 11 |
| `upload/ReelUploadActivity.java` | PATCHED | Fix 7 |
| `core/models/ReelModel.java` | PATCHED | Fix 4, 10, 11 |
| `workers/PushNotify_DuetInvite_Patch.java` | PATCH NOTES | Fix 11 |
| `upload/ReelUploadActivity_DuetPatch.java` | NOTES | Fix 7 docs |

---

## Integration Steps

1. **Copy all files** from this zip into your Android project at the same package paths
2. **AndroidManifest.xml**: register 3 new activities:
   ```xml
   <activity android:name=".player.SingleReelPlayerActivity" />
   <activity android:name=".social.DuetInviteActivity" />
   <!-- DuetPreviewOverlayView is a View, not an Activity — no registration needed -->
   ```
3. **activity_duet_reel.xml**: add these views:
   ```xml
   <!-- Fix 2: bubble drag overlay — existing view, no changes needed -->
   <View android:id="@+id/view_bubble_overlay" ... />
   <!-- Fix 5: preview overlay -->
   <com.callx.app.social.DuetPreviewOverlayView android:id="@+id/view_duet_preview_overlay"
       android:layout_width="match_parent" android:layout_height="match_parent" />
   <!-- Fix 10: chain duet banner -->
   <TextView android:id="@+id/tv_chain_duet_banner" ... />
   <!-- Fix 11: invite button -->
   <ImageButton android:id="@+id/btn_invite_duet" ... />
   ```
4. **activity_single_reel_player.xml**: create new layout for SingleReelPlayerActivity
5. **activity_duet_invite.xml**: create new layout for DuetInviteActivity
6. **PushNotify.java**: paste the notifyDuetInvite() method from PushNotify_DuetInvite_Patch.java
7. **ReelPlayerFragment / ReelMoreBottomSheet caller**: pass `reel.musicUrl` as EXTRA_ORIGINAL_SOUND_URL when launching DuetReelActivity (Fix 4)
8. **Firebase Security Rules**: add rule for `duetInvites` node:
   ```json
   "duetInvites": {
     "$uid": {
       ".read": "auth.uid === $uid",
       ".write": "auth != null"
     }
   }
   ```
