# CallX — Call System Fixes v4

## Bugs Fixed

### BUG FIX #1 — `onNewIntent()` Missing (CRITICAL)
**File:** `feature-calls/.../call/CallActivity.java`

**Problem:** `CallActivity` uses `FLAG_ACTIVITY_SINGLE_TOP`. Jab MainActivity ka "Return to Call" banner tap hota tha aur CallActivity already back-stack mein hoti thi, toh `onCreate()` nahi chalta tha — sirf `onNewIntent()` chalta hai. Pehle `onNewIntent()` override hi nahi tha, isliye banner tap pe koi UI sync nahi hota tha.

**Fix:** `onNewIntent()` add kiya gaya. Wapas aane par `updateMicUI()`, `updateCameraUI()`, aur `updateHoldUI()` call hote hain — UI hamesha fresh dikhta hai.

---

### BUG FIX #2 — Bluetooth Speaker Button UI Not Updating
**File:** `feature-calls/.../call/CallActivity.java` → `registerBtScoReceiver()`

**Problem:** BT headset connect hone par `tvSpeakerLabel` toh "Bluetooth" ho jaata tha, lekin `btnToggleSpeaker.setAlpha()` update nahi hoti thi — button dimmed/faded dikhta tha jab BT active tha.

**Fix:** `SCO_AUDIO_STATE_CONNECTED` pe `runOnUiThread()` mein `btnToggleSpeaker.setAlpha(1f)` add kiya taaki button pura lit dikhe.

---

### BUG FIX #3 — Back Button Ends Video Call
**File:** `feature-calls/.../call/CallActivity.java` → `onBackPressed()`

**Problem:** Video call mein back button press karne par `endCall()` call hota tha — call abruptly end ho jaati thi. WhatsApp, Google Meet sab minimize karte hain.

**Fix:** Video call + connected state mein back button → `enterPipMode()` (minimize to PiP). Audio calls mein back = end call (expected behavior).

---

### BUG FIX #4 — "Declined" vs "No Answer" Same Message
**File:** `feature-calls/.../call/CallActivity.java` → `watchCallStatus()`

**Problem:** Caller ko dono cases mein same generic message dikhta tha — chahe partner ne actively decline kiya ho ya koi pickup hi nahi kiya ho.

**Fix:**
- `"rejected"` status → `"Call declined"` (partner ne decline kiya)
- `"cancelled"` status → call silently end (caller ne khud cancel kiya — no message shown)
- `"busy"` → `"[Name] is on another call"` (unchanged)

---

## New Features Added

### FEATURE #1 — Remote Video Full-Screen Tap
**File:** `feature-calls/.../call/CallActivity.java` → `setupRemoteVideoTap()`, `toggleRemoteFullscreen()`

Video call mein partner ka video tap karne par:
- Saare controls (buttons, labels, local preview) hide ho jaate hain
- Full immersive remote video experience milti hai
- Dobara tap karne par sab wapas aa jaate hain

---

### FEATURE #2 — Hold / Resume
**File:** `feature-calls/.../call/CallActivity.java` → `toggleHold()`, `updateHoldUI()`, `watchRemoteHoldState()`

Hold button (null-safe — existing layouts support karte hain `btnHold` agar ho):
- Mic mute + video paused + Firebase mein `holdState/{uid}` = `true`
- Partner ke screen pe "on hold" overlay dikhta hai
- Timer ruk jaata hai — "Call on hold" dikhta hai
- Resume karne par sab wapas normal ho jaata hai
- `onDestroy()` mein `remoteHoldListener` properly detach hota hai

---

### FEATURE #3 — TURN Credentials Periodic Refresh
**File:** `feature-calls/.../call/CallActivity.java` → `scheduleTurnRefresh()`

Long calls (>30 minutes) mein TURN server credentials expire ho jaate hain → ICE candidates fail → call drop.

**Fix:** Call connect hone ke 30 minute baad (aur phir har 30 min mein) ek controlled ICE restart trigger hota hai jo fresh TURN credentials pick up karta hai. Sirf `callConnected && !finishing` state mein chalta hai — idle/ended calls pe nahi.

`onDestroy()` mein `turnRefreshHandler` properly cancel hota hai.

---

## Files Modified

| File | Changes |
|------|---------|
| `feature-calls/.../call/CallActivity.java` | All 4 bug fixes + 3 new features |

## Files Unchanged (already working correctly)
- `MainActivity.java` — Return-to-call banner already implemented ✓
- `IncomingCallActivity.java` — No issues found ✓
- `CallForegroundService.java` — Static fields for banner already present ✓
- `AddNoteActivity.java` — Voice/video note flow correct ✓
