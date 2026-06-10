# CallX2 — v9: Collab Duet System

**Release:** v9_CollabDuet  
**Base:** v8_AllGapsFixed  
**Date:** June 2026

---

## Feature Overview

**Collab Duet** allows two users to record a side-by-side duet **simultaneously** in real time.

```
┌─────────────────┬─────────────────┐
│  Original Reel  │  Partner Camera │   ← top half (live preview)
│  (ExoPlayer)    │  (JPEG frames)  │
├─────────────────┴─────────────────┤
│        Self Camera (CameraX)      │   ← bottom half
├────────────────────────────────────┤
│   [I'm Ready ✅]     [Stop ⏹]   │
└────────────────────────────────────┘
```

---

## User Flow

1. User long-presses the **🔀 Duet** button on any reel
2. Alert shows: **"Solo Duet"** vs **"👥 Collab Duet (invite a friend)"**
3. Tapping "Collab Duet" opens **CollabDuetInviteActivity** — searchable list of followers
4. User selects a partner → session created in Firebase RTDB
5. **Partner receives FCM push** (`collab_duet_invite`) with rich notification + "Join Now" action
6. Both land in **CollabDuetSessionActivity**:
   - Host sees: original reel | "Waiting for partner..." | own camera
   - Partner sees: original reel | host's live preview | own camera
7. Both tap **"I'm Ready ✅"** → Firebase sets `both_ready = true`
8. Host computes `startAtMillis = now + 4200ms` → shared countdown visible on both screens
9. At `startAtMillis`: ExoPlayer starts + CameraX recording begins simultaneously
10. During recording: compressed JPEG frames uploaded every 600ms → partner preview updates
11. Either user taps **"Stop ⏹"** (or auto-stop at reel duration)
12. Both recordings upload to Firebase Storage → `CollabDuetCompositorWorker` enqueued
13. Worker downloads both videos → MediaCodec side-by-side composite → uploads final MP4
14. New reel entry written to `reels/{newReelId}`, indexed under both users' `reelsByUser` paths

---

## New Files

| File | Purpose |
|------|---------|
| `social/collab/CollabDuetSession.java` | Firebase RTDB model (status, UIDs, video URLs) |
| `social/collab/CollabDuetSessionActivity.java` | Combined host+partner recording screen |
| `social/collab/CollabDuetInviteActivity.java` | Follower picker → session creator |
| `social/collab/CollabVideoCompositor.java` | MediaCodec side-by-side compositor |
| `workers/CollabDuetCompositorWorker.java` | WorkManager job: download → composite → upload → write reel |
| `res/layout/activity_collab_duet_session.xml` | Recording screen layout |
| `res/layout/activity_collab_duet_invite.xml` | Follower invite list layout |
| `res/layout/item_follower_collab_invite.xml` | Follower list item |

## Modified Files

| File | Change |
|------|--------|
| `feed/ReelPlayerFragment.java` | `openCollabDuet()` method; long-press on duet button shows Solo/Collab dialog |
| `notifications/ReelFCMNotificationHandler.java` | `TYPE_COLLAB_DUET_INVITE` + `TYPE_COLLAB_DUET_ACCEPT` handling + rich invite notification |
| `core/FirebaseUtils.java` | `getCollabDuetSessionsRef()` + `getCollabDuetFramesRef(sessionId)` |
| `feature-reels/build.gradle` | Added `firebase-storage` |
| `feature-reels/AndroidManifest.xml` | Registered `CollabDuetInviteActivity` + `CollabDuetSessionActivity` (with deep-link intent-filter) |

---

## Firebase RTDB Structure

```
collabDuetSessions/
  {sessionId}/
    sessionId:       "abc123"
    status:          "waiting" | "both_ready" | "recording" | "done" | "declined"
    createdAt:       1234567890
    reelId:          "originalReelId"
    reelVideoUrl:    "https://..."
    reelThumbUrl:    "https://..."
    hostUid:         "uid1"
    hostName:        "user1"
    hostReady:       true
    hostVideoUrl:    "gs://..."         ← written after upload
    partnerUid:      "uid2"
    partnerName:     "user2"
    partnerReady:    true
    partnerVideoUrl: "gs://..."         ← written after upload
    startAtMillis:   1234567894200      ← synchronized countdown target
    durationMs:      30000
    compositedReelId: "newReelId"       ← written by compositor

collabDuetFrames/
  {sessionId}/
    {uid}:  "<base64-jpeg>"             ← updated every 600ms during recording
```

## Firebase Storage Structure

```
collab_duets/{sessionId}/{uid}.mp4         ← individual recordings
collab_duets_composed/{sessionId}/composed.mp4  ← final composite
```

---

## Server-Side: FCM Invite Notification

Send this when a new `collabDuetSessions` entry is written with `status == "waiting"`:

```json
{
  "reel_notif_type": "collab_duet_invite",
  "sender_name":    "<host display name>",
  "sender_photo":   "<host avatar URL>",
  "sender_uid":     "<host UID>",
  "reel_id":        "<original reel ID>",
  "reel_thumb":     "<original reel thumb URL>",
  "session_id":     "<collabDuetSessions push key>"
}
```

Target: partner's FCM token (`users/{partnerUid}/fcmToken`)

---

## Firebase Security Rules (add to database.rules.json)

```json
"collabDuetSessions": {
  "$sessionId": {
    ".read":  "auth != null && (data.child('hostUid').val() == auth.uid || data.child('partnerUid').val() == auth.uid)",
    ".write": "auth != null && (data.child('hostUid').val() == auth.uid || data.child('partnerUid').val() == auth.uid || !data.exists())"
  }
},
"collabDuetFrames": {
  "$sessionId": {
    "$uid": {
      ".read":  "auth != null",
      ".write": "auth != null && auth.uid == $uid"
    }
  }
}
```

---

*End of v9 Collab Duet upgrade notes.*
