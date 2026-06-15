# CallX2 Status System — v27 Upgrade Notes

**Release:** v27_StatusMissingFixed  
**Base:** v26 (StatusFixed)  
**Date:** June 2026

---

## Summary

6 critical gaps in the status system fixed. 4 new feature screens added.

---

## 🔴 Critical Bug Fixes

### FIX 1 — StatusHighlightsActivity missing from Manifest
**File:** `feature-status/src/main/AndroidManifest.xml`

`StatusHighlightsActivity` existed as a Java file but was NOT registered in the
manifest. Any intent to launch it would crash with `ActivityNotFoundException`.

**Fix:** Added `<activity android:name="com.callx.app.highlights.StatusHighlightsActivity"/>` to manifest.

---

### FIX 2 — StatusArchiveActivity missing from Manifest
**File:** `feature-status/src/main/AndroidManifest.xml`

Same issue — file existed, manifest entry missing. Tapping "Archive" shortcut in
`StatusFragment` would crash on Android.

**Fix:** Added `<activity android:name="com.callx.app.archive.StatusArchiveActivity"/>` to manifest.

---

### FIX 3 — StatusNotificationHelper channel not explicitly created
**File:** `app/src/main/java/com/callx/app/CallxApp.java`

`StatusNotificationHelper.CHANNEL_ID = "callx_status"` matched `Constants.CHANNEL_STATUS`
but `createChannel()` was never called explicitly via the helper. Added explicit call
in `createChannels()` for safety on future refactors.

**Fix:** Added `StatusNotificationHelper.createChannel(this)` inside `createChannels()`.

---

## 🟠 New Features

### FEATURE 1 — Music Picker for New Status
**New File:** `feature-status/.../interactions/StatusMusicPickerBottomSheet.java`  
**Modified:** `feature-status/.../compose/NewStatusActivity.java`

- Bottom sheet with 15 curated popular tracks (Bollywood + International)
- Search filter for track name / artist
- Manual audio URL input (MP3/M4A)
- "Remove music" toggle if already attached
- On selection: `item.musicTitle`, `item.musicArtist`, `item.musicUrl` are saved to Firebase
- In viewer: existing music strip already picks these fields up automatically (no viewer change needed)

**Integration:** Add `tag="btn_music"` to any Button/TextView in `activity_new_status.xml` to wire the picker.

---

### FEATURE 2 — Question Box Answers Viewer
**New File:** `feature-status/.../interactions/StatusQuestionAnswersBottomSheet.java`  
**Modified:** `feature-status/.../viewer/StatusViewerActivity.java`

- Owner's "question box" status now shows a **"👁 View Answers (N)"** button instead of the blank input
- Bottom sheet shows all submitted answers with:
  - Viewer name + avatar (loaded from Firebase users node)
  - Answer text in purple bubble
  - Total response count badge
- Answers stored in Firebase: `statusQuestionAnswers/{ownerUid}/{statusId}/{viewerUid}`
- `StatusQuestionAnswersBottomSheet.submitAnswer()` helper method for recording answers

---

### FEATURE 3 — Poll Results Sheet  
**New File:** `feature-status/.../interactions/StatusPollResultsBottomSheet.java`  
**Modified:** `feature-status/.../viewer/StatusViewerActivity.java`

- When owner views own poll status: `tvPollTotal` becomes tappable "See Results ▶"
- Full bottom sheet with:
  - Poll question at top in purple card
  - Each option with animated progress bar + percentage + vote count
  - 🏆 "Leading" badge on winning option
  - ✓ indicator on user's own vote
  - "No votes yet" empty state with CTA

---

### FEATURE 4 — Close Friends Manager Screen  
**New File:** `feature-status/.../closefriends/CloseFriendsManagerActivity.java`  
**Modified:**
  - `feature-status/src/main/AndroidManifest.xml` — registered new activity
  - `feature-status/.../privacy/StatusPrivacyBottomSheet.java` — "Close friends ⭐" mode now opens manager

Features:
- Full contact list with search bar
- ⭐ star toggle on each row — tap to add/remove from close friends
- Live badge: "Close Friends (N)" in toolbar
- Sorted: close friends first, then alphabetical
- Firebase sync + local SharedPreferences cache (via StatusCloseFriendsManager)
- Opens automatically when user selects "Close friends ⭐" in StatusPrivacyBottomSheet

---

## Files Changed

| File | Type | Change |
|------|------|--------|
| `feature-status/src/main/AndroidManifest.xml` | Modified | Added 3 activity entries (Highlights, Archive, CloseFriendsManager) |
| `app/.../CallxApp.java` | Modified | Added `StatusNotificationHelper.createChannel(this)` |
| `feature-status/.../closefriends/CloseFriendsManagerActivity.java` | **NEW** | Close Friends list manager |
| `feature-status/.../interactions/StatusMusicPickerBottomSheet.java` | **NEW** | Music track picker for status creation |
| `feature-status/.../interactions/StatusQuestionAnswersBottomSheet.java` | **NEW** | Question box answers viewer for owner |
| `feature-status/.../interactions/StatusPollResultsBottomSheet.java` | **NEW** | Poll results sheet with progress bars |
| `feature-status/.../compose/NewStatusActivity.java` | Modified | Music picker integration + music fields in saveStatus |
| `feature-status/.../viewer/StatusViewerActivity.java` | Modified | Poll results + question answers integration for owner |
| `feature-status/.../privacy/StatusPrivacyBottomSheet.java` | Modified | "Close friends" mode opens CloseFriendsManagerActivity |

---

## Layout Changes Needed (Manual)

To show the **Music button** in `activity_new_status.xml`, add:

```xml
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="♫ Add Music"
    android:tag="btn_music"
    android:padding="8dp"
    android:textColor="#6200EE" />
```

The button is wired via `findViewWithTag("btn_music")` in `NewStatusActivity.onCreate()`.

