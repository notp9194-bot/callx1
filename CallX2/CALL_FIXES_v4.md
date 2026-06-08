# CallX — Call System Fixes & New Features v4

**Date:** June 2026  
**Focus:** 4 Missing Features Implemented — TURN Fallback, RTCStats Quality, Filter Chips, Custom Ringtone

---

## ✅ Summary of Changes

| Feature | Files Changed | Status |
|---|---|---|
| Free TURN fallback servers | `CallActivity.java`, `GroupCallActivity.java`, `Constants.java` | ✅ NEW |
| RTCStats real quality monitoring | `CallActivity.java` | ✅ NEW |
| Incoming / Outgoing filter chips | `CallsFragment.java`, `fragment_calls.xml` | ✅ NEW |
| Custom call ringtone | `IncomingRingService.java`, `CallRingtonePickerActivity.java` (NEW), `Constants.java` | ✅ NEW |

---

## 🔴 Fix 1: Free TURN Fallback Servers

### Problem
`buildFallbackIce()` sirf Google STUN servers (`stun.l.google.com`) use karta tha.
Corporate networks, mobile hotspots, aur strict NAT ke peeche STUN se peer-to-peer
connection nahi banta — calls fail ho jaati thi silently.

TURN server relay karta hai jab direct P2P connection possible nahi hoti.

### Solution
`Constants.java` mein Metered open-relay free TURN servers add kiye.
`buildFallbackIce()` ab in servers ko `SERVER_URL` ke TURN fail hone par use karta hai.

```java
// Constants.java
public static final String TURN_FREE_1   = "turn:openrelay.metered.ca:80";
public static final String TURN_FREE_2   = "turn:openrelay.metered.ca:443";
public static final String TURN_FREE_TLS = "turns:openrelay.metered.ca:443";
public static final String TURN_FREE_USER = "openrelayproject";
public static final String TURN_FREE_CRED = "openrelayproject";
```

**Files changed:** `Constants.java`, `CallActivity.java`, `GroupCallActivity.java`

> **Note:** Production mein apna TURN server lagao (Coturn / Metered.ca paid).
> Ye free servers bandwidth-limited hain lekin fallback ke liye kaafi hain.

---

## 🔴 Fix 2: RTCStats-Based Real Network Quality

### Problem
`showQualityIndicator()` sirf ICE connection state se quality guess karta tha:
- `CONNECTED` → "Good"
- `DISCONNECTED` → "Weak"
- `FAILED` → "Poor"

Ye sahi nahi tha. ICE connected ho sakti hai lekin network 40kbps pe chal raha ho — 
display "Good" dikhata tha jabki call choppy thi.

### Solution
`peerConnection.getStats()` se real throughput fetch karo har 4 second mein.
Quality thresholds real bitrate se decide hote hain:

| Quality | Condition |
|---|---|
| **Good** | ≥200 kbps total + packet loss <5% |
| **Weak** | 60–200 kbps ya loss 5–15% |
| **Poor** | <60 kbps ya loss >15% |

Quality label ab actual stats dikhata hai:
```
Good • 450↓ 220↑ kbps
Weak • 89↓ 42↑ kbps
Poor • 12↓ 8↑ kbps
```

`applyVideoBitrate()` bhi automatically Poor/Weak pe VGA aur Good pe HD pe switch karta hai.

**Methods added to CallActivity.java:**
- `startRtcStatsPolling()` — call connect hone par start hota hai
- `stopRtcStatsPolling()` — releaseWebRTC() mein stop hota hai  
- `pollRtcStats()` — har 4 second pe getStats() call karta hai

**Files changed:** `CallActivity.java`

---

## 🟠 Fix 3: Incoming / Outgoing Filter Chips

### Problem
Calls tab mein sirf **All** aur **Missed** chips the.
User specifically incoming ya outgoing calls filter nahi kar sakta tha.

### Solution
Do naye chips add kiye gaye:
- **Incoming** — sirf received calls dikhao
- **Outgoing** — sirf made calls dikhao

Chips sequence: **All → Missed → Incoming → Outgoing → Contacts → Non-spam → Spam**

`applyFilter()` mein naye cases:
```java
case "incoming":
    if (!dir.equals("incoming")) continue; break;
case "outgoing":
    if (!dir.equals("outgoing")) continue; break;
```

**Files changed:** `CallsFragment.java`, `fragment_calls.xml`

---

## 🟠 Fix 4: Custom Call Ringtone

### Problem
Incoming calls sirf system default ringtone bajati thi.
User apna ringtone set nahi kar sakta tha.

### Solution

#### IncomingRingService.java
`startRingtone()` ab SharedPrefs se custom ringtone URI padhta hai:
```java
SharedPreferences prefs = getSharedPreferences(PREF_CALL_SETTINGS, MODE_PRIVATE);
String customUriStr = prefs.getString(PREF_CALL_RINGTONE_URI, null);
Uri ringtoneUri = (customUriStr != null)
    ? Uri.parse(customUriStr)
    : RingtoneManager.getDefaultUri(TYPE_RINGTONE);
```

#### CallRingtonePickerActivity.java (NEW)
Android system ringtone picker kholta hai, user jo select kare save kar deta hai.

**Integration (settings screen mein add karo):**
```java
// Settings se call karo:
startActivity(new Intent(context, CallRingtonePickerActivity.class));
```

**AndroidManifest mein register karo:**
```xml
<activity android:name=".settings.CallRingtonePickerActivity"
    android:theme="@style/Theme.AppCompat.Translucent"
    android:label="Call Ringtone"/>
```

**Files changed/added:** `IncomingRingService.java`, `CallRingtonePickerActivity.java` (NEW), `Constants.java`

---

## AndroidManifest Changes Required

```xml
<!-- feature-calls/src/main/AndroidManifest.xml mein add karo: -->
<activity
    android:name=".settings.CallRingtonePickerActivity"
    android:exported="false"
    android:label="Call Ringtone"/>
```

---

## Kya Nahi Badla (Already Implement Tha)

| Feature | Status |
|---|---|
| 1-to-1 Audio + Video Call (WebRTC) | ✅ Already complete |
| Group Call (8 participants mesh) | ✅ Already complete |
| Call waiting ("busy" signal) | ✅ Already via CallForegroundService.isRunning |
| PiP mode | ✅ Already complete |
| ICE restart | ✅ Already complete |
| Missed call notification (colorful) | ✅ Already complete |
| Bluetooth SCO | ✅ Already complete |
| Firebase onDisconnect cleanup | ✅ Already complete |
| Screen share | ❌ Not implemented (complex, needs MediaProjection API) |
| Call recording | ❌ Not implemented (legal considerations) |
| TelecomManager / CallKit | ❌ Not implemented |

