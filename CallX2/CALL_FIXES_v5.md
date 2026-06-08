# CallX — Call Recording Feature (v5)

**Date:** June 2026  
**Feature:** In-call audio recording with MediaRecorder + permission handling

---

## Overview

Users can now record any active call directly from the call screen.

- **Record button** appears in the call UI controls (Row 1: Speaker | **Record** | Flip)
- Tap to start → pulsing red indicator shows recording is active
- Tap again to stop → file saved to device storage
- Recording auto-stops and saves when call ends

---

## Files Added / Changed

| File | Change |
|---|---|
| `CallRecorderHelper.java` | NEW — MediaRecorder wrapper |
| `activity_call.xml` | Record button added to Row 1 |
| `CallActivity.java` | Recording logic, permission handling |
| `drawable/ic_record.xml` | NEW — red circle icon |
| `AndroidManifest.xml` | RECORD_AUDIO + WRITE_EXTERNAL_STORAGE permissions |
| `Constants.java` | RECORDING_DIR_NAME, RECORDING_FILE_PREFIX |

---

## How It Works

### Audio Source: VOICE_COMMUNICATION

```java
recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
```

Android mein VoIP apps ke liye `VOICE_COMMUNICATION` audio source dono sides capture karta hai
(microphone + speaker mix) most devices pe — iska matlab dono parties ki awaaz record hoti hai.

> **Device note:** Kuch strict Android OEMs (especially Xiaomi/MIUI) pe sirf mic side
> record hoti hai. Ye Android ka limitation hai, app-level fix available nahi hai.

### Output Format

- Container: MPEG-4 (`.m4a`)
- Codec: AAC at 64 kbps, 16 kHz — best quality/size ratio for voice
- Storage: `<externalFilesDir>/CallRecordings/CallX_<name>_<date>.m4a`
  - Example: `CallX_Alice_Smith_2025-06-08_14-30-22.m4a`
  - Android 10+: No storage permission needed (`externalFilesDir` is app-private)
  - Android 9 and below: `WRITE_EXTERNAL_STORAGE` (already declared in manifest)

### Permissions Flow

```
User taps Record button
      ↓
RECORD_AUDIO granted?
  YES → startCallRecording()
  NO  → requestPermissions() → onRequestPermissionsResult()
              ↓
          Granted → startCallRecording()
          Denied  → Toast "Mic permission chahiye"
```

> `RECORD_AUDIO` pe ek special note: Google Play policy mein ye sensitive permission hai.
> Privacy policy mein call recording mention karna zaroori hai before publishing.

---

## Integration Checklist

### 1. Build & run — no extra steps needed
Recording is self-contained. `CallRecorderHelper` is instantiated inside `CallActivity`.

### 2. AndroidManifest (already updated)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"/>
```

### 3. Access recorded files

Files `getExternalFilesDir(null)/CallRecordings/` mein hain.
Tumhara "Recorded Calls" screen in files list kar ke users ko dikhao:

```java
File dir = new File(context.getExternalFilesDir(null), Constants.RECORDING_DIR_NAME);
File[] recordings = dir.listFiles((f, n) -> n.endsWith(".m4a"));
Arrays.sort(recordings, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
```

### 4. Share / export a recording

```java
Uri fileUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
Intent share = new Intent(Intent.ACTION_SEND);
share.setType("audio/mp4");
share.putExtra(Intent.EXTRA_STREAM, fileUri);
startActivity(Intent.createChooser(share, "Share Recording"));
```

(FileProvider registration bhi manifest mein zaroori hai — see HOW_TO_INTEGRATE.md)

---

## Known Limitations

| Limitation | Reason |
|---|---|
| Some devices record mic-only | VOICE_COMMUNICATION source OEM-dependent |
| Background recording not supported | MediaRecorder needs active Activity |
| No cloud upload | Architecture decision — local-first, user controls data |
| Group calls not recorded | GroupCallActivity alag hai; same pattern follow karo |

---

## Future Improvements (Out of Scope for v5)

- **Recordings List screen** — show all saved `.m4a` files with play/share/delete
- **Cloud backup** — Firebase Storage mein optional upload
- **Group call recording** — same `CallRecorderHelper` pattern in `GroupCallActivity`
- **Waveform visualization** — `MediaRecorder.getMaxAmplitude()` se live level meter

