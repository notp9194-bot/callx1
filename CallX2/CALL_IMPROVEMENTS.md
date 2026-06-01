# CallX2 — Call System Production Upgrades

## Summary
Comprehensive production-level upgrade to the 1:1 and group call system.

---

## New Files

### `feature-calls/.../utils/CallNetworkMonitor.java`
Real-time call quality indicator using WebRTC `RTCStatsReport`.
- Polls every 3 seconds: packet loss, RTT, jitter
- Four quality levels: **Excellent / Good / Fair / Poor**
- Android `NetworkCallback` for connectivity events (onNetworkLost, onNetworkRestored)
- Callback interface for UI updates (`onQualityChanged`, `onNetworkLost`, `onNetworkRestored`)

### `feature-calls/.../utils/CallAudioFocusManager.java`
Centralized `AudioFocus` lifecycle for all calls.
- `requestFocus()` before WebRTC init → exclusive `AUDIOFOCUS_GAIN`
- Android O+ `AudioFocusRequest` API with `USAGE_VOICE_COMMUNICATION`
- `configureForCall(speakerOn)` sets `MODE_IN_COMMUNICATION`
- `abandonFocus()` + `restoreAudio()` on call end → other apps resume

### `feature-calls/.../utils/CallEncryptionHelper.java`
DTLS-SRTP status verification from `RTCStatsReport`.
- Reads `transport` stats: `dtlsState`, `srtpCipher`
- Status levels: `ENCRYPTED / PENDING / UNENCRYPTED / UNKNOWN`
- Shows 🔒 Encrypted badge once DTLS handshake is confirmed

### `feature-calls/.../activities/CallSettingsActivity.java`
1:1 call settings screen (mirrors `GroupCallSettingsActivity`).
- **Audio**: noise suppression, echo cancellation, auto gain control
- **Video**: HD/SD resolution, 30/24/15 fps
- **Routing**: earpiece/speaker default, auto-speaker on video
- **Notifications**: lock screen name, silent ringtone, missed call
- **Privacy**: show "in a call" status, block unknown callers
- **Advanced**: data saver (caps to SD bitrate), keep screen on
- `SharedPreferences` key: `call_settings_1to1`

### `core/.../repository/CallLogRepository.java`
Offline-first repository for call history.
- Serves local Room cache immediately
- Syncs up to 200 latest logs from Firebase
- `insertLog()`, `deleteLog()` (both stores), `loadLogs(uid, cb)`
- Thread-safe singleton, background executor

---

## Improved Files

### `CallActivity.java`
- ✅ `CallAudioFocusManager`: requestFocus on init, abandonFocus on end
- ✅ `CallNetworkMonitor`: starts after ICE CONNECTED, shows quality chip in UI
- ✅ `CallEncryptionHelper`: checks encryption status after connection, shows badge
- ✅ **Bluetooth SCO**: detects headset, registers `ACTION_SCO_AUDIO_STATE_UPDATED`
- ✅ **Settings integration**: reads noise suppression, echo cancel, video quality, FPS, data saver from `SharedPreferences`
- ✅ **Data saver mode**: caps video to VGA/15fps when enabled
- ✅ **ICE restart state machine**: exponential backoff (2s → 4s → 8s), max 3 restarts
- ✅ **Reconnecting overlay**: shows/hides `tvReconnecting` chip on ICE disconnect
- ✅ **Settings button**: `btnSettings` opens `CallSettingsActivity`
- ✅ **Mute notification sync**: broadcasts `updateMic` extra to `CallForegroundService`
- ✅ **Proper cleanup**: `releaseWebRTC()` disposes all resources, BT SCO stopped

### `IncomingCallActivity.java`
- ✅ **Avatar loading**: Glide with thumb→full photo fallback, `ic_default_avatar` placeholder
- ✅ **VibrationEffect API** (Android O+): `createWaveform` with repeat pattern
- ✅ **Caller cancel detection**: Firebase `status` listener → "Call ended" overlay
- ✅ **Broadcast receiver**: handles `ACTION_ACCEPT_CALL`/`ACTION_DECLINE_CALL` from notification
- ✅ **Auto-reject**: logs missed call before declining on timeout
- ✅ **Lock screen flags**: `FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD | FLAG_TURN_SCREEN_ON`

### `CallForegroundService.java`
- ✅ **Mute status tracking**: shows "🔇 Muted • 1:23" in notification subtitle
- ✅ **Avatar download with retry**: exponential backoff, max 3 attempts
- ✅ **Android 12+ CallStyle**: green ongoing call chip via `NotificationCompat.CallStyle`
- ✅ **START_STICKY**: OS can restart service; preserves last-known call state
- ✅ **Live timer**: updates notification every second
- ✅ `ic_call_notification` small icon

### `Constants.java` (additions)
- `CALL_SETTINGS_1TO1_PREFS` — SharedPreferences name for 1:1 call settings
- `ACTIVE_SPEAKER_THRESHOLD` / `ACTIVE_SPEAKER_SILENCE_MS` — group call speaker detection
- `ICE_RESTART_DELAY_MS` / `ICE_RESTART_BACKOFF` — ICE reconnect config
- `ACTION_CALL_QUALITY_UPDATE` — broadcast action for quality events

---

## Layout Requirements (add to existing layouts)

### `activity_call.xml` — add these views:
```xml
<!-- Network quality chip -->
<TextView android:id="@+id/tvNetworkQuality" ... />

<!-- DTLS encryption badge -->
<TextView android:id="@+id/tvEncryptionBadge" ... />

<!-- Reconnecting overlay -->
<TextView android:id="@+id/tvReconnecting" android:text="Reconnecting…" ... />

<!-- Settings button -->
<ImageButton android:id="@+id/btnSettings" ... />
```

### `activity_call_settings.xml` (NEW)
Full settings screen with Switch + RadioGroup widgets matching keys in `CallSettingsActivity`.

---

## AndroidManifest.xml — add:
```xml
<activity android:name=".activities.CallSettingsActivity"
    android:label="Call Settings"
    android:exported="false" />
```
