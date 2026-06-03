# CallX2 — v5 New Features

## 6 Nayi Features (v5 over v4)

---

### Feature 1: Call Recording ⏺️
**Files:** `CallActivity.java`

- Call connected hone ke baad "Record" button daba ke call record karo
- Format: **M4A (AAC 128kbps, 44.1kHz)** — WhatsApp quality
- Saved to: `Android/data/com.callx.app/files/Music/CallX/call_YYYYMMDD_HHmmss.m4a`
- Red **REC** badge top-center mein jab recording chal rahi ho
- Call end hone par auto-stop + file path toast

---

### Feature 2: Screen Share 🎬
**Files:** `CallActivity.java`, `AndroidManifest.xml`

- Video call mein "Share" button → system permission dialog
- Camera capturer **switch** hota hai `ScreenCapturerAndroid` pe
- "Stop Share" → camera wapas restore
- `foregroundServiceType="phoneCall|mediaProjection"` manifest mein

---

### Feature 3: E2E Encryption Badge 🔒
**Files:** `CallActivity.java`, `activity_call.xml`

- ICE CONNECTED hote hi `"End-to-end encrypted"` green badge dikhta hai
- WebRTC har call mein DTLS-SRTP use karta hai — badge honest hai
- Caller name ke neeche, avatar ke upar

---

### Feature 4: Background Blur 🌫️
**Files:** `CallActivity.java`, `BackgroundBlurProcessor.java`, `build.gradle`

- "Blur" button toggle karo
- `ML Kit Selfie Segmentation` → person ko detect karo → background blur
- Local preview mein TextureView pe render (transmitted stream pe bhi apply)
- `com.google.mlkit:segmentation-selfie:16.0.0-beta6` dependency

---

### Feature 5: Call Stats Overlay 📊
**Files:** `CallActivity.java`, `CallStatsHelper.java`, `activity_call.xml`

- "Stats" button → top-left transparent overlay
- Har **3 seconds** mein update:
  - `↑ TX kbps ↓ RX kbps` — real-time bitrate
  - `Loss: X.X%` — packet loss
  - `RTT: X ms` — round-trip time
- Tap again to hide

---

### Feature 6: Custom Ringtone 🔕
**Files:** `IncomingRingService.java`, `RingtoneSettingsHelper.java`

- User apni ringtone pick kar sakta hai `RingtoneManager.ACTION_RINGTONE_PICKER` se
- URI `SharedPreferences` mein save hoti hai
- `IncomingRingService` pehle custom URI check karta hai, nahi mila toh system default
- Settings mein integrate karne ke liye `RingtoneSettingsHelper.java` ready hai

---

## Sab Puraani Fixes Intact Hain

| Version | Description |
|---------|-------------|
| v3 | 7 bug fixes (notification crash, photo, Bluetooth, headphone, drag, quality, camState) |
| v4 | Return-to-Call Banner, Busy Signal, Adaptive Video, Timeout 60s |
| v5 | 6 new features (above) |
