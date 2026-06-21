# On-Call Strip — Upgrade Notes

## Feature: "Partner is On a Call" Strip in Chat

Jab partner kisi se call pe ho, chat screen me header ke neeche ek animated green pill strip dikhta hai — normal `online` status ke saath, use replace kiye bina.

---

## Kya dikhta hai

| Condition | Strip Text |
|---|---|
| Partner voice call pe hai | 📞 `<Name> is on a call` |
| Partner video call pe hai | 📹 `<Name> is on a video call` |
| Call khatam hua | Strip slide-up fade-out ke saath gayab |

---

## Files Changed

### `core/src/main/java/com/callx/app/utils/PresenceManager.java`
- `setOnCall(boolean)` → `setOnCall(boolean, String callType)` me upgrade
- `callType` = `"voice"` ya `"video"` (null when clearing)
- Firebase pe `users/{uid}/onCallType` node bhi write karta hai
- `onDisconnect()` dono nodes pe — process kill hone pe auto-clear
- Legacy `setOnCall(boolean)` overload rakha gaya backward compat ke liye

### `feature-calls/.../call/CallActivity.java`
- `onCallConnected()` → `setOnCall(true, isVideo ? "video" : "voice")`
- `abandonCallAudioFocus()` → `setOnCall(false, null)`

### `feature-calls/.../group/GroupCallActivity.java`
- ICE `CONNECTED` case (first connection) → `setOnCall(true, isVideo ? "video" : "voice")`
- `endCall()` → `setOnCall(false, null)` (added at top, before cleanup)

### `feature-chat/.../controllers/ChatPresenceController.java`
- `watchPartnerOnCall()` — ab `onCallType` bhi listen karta hai
- `buildOnCallLabel()` — voice vs video emoji differentiate karta hai
- `refreshOnCallStripLabel()` — callType update aane pe label live refresh
- `startCallDotPulse()` / `pulseCallDot()` / `stopCallDotPulse()` — pulsing green dot animation (alpha 0.3 ↔ 1.0, 700ms cycle)
- `release()` — `onCallTypeListener` cleanup + `stopCallDotPulse()` added

---

## Firebase Structure

```
users/
  {uid}/
    online: true/false
    lastSeen: timestamp
    onCall: true/false          ← existing node
    onCallType: "voice"|"video" ← NEW node
```

---

## Layout (activity_chat.xml)

`ll_on_call_strip` pehle se exist karta hai. `dot_on_call_pulse` (green circle) ab animated hai via `ChatPresenceController#startCallDotPulse`.

Strip position: `top|center_horizontal` over FrameLayout, marginTop 6dp — screenshot banner ke neeche, watching banner ke upar. Dono simultaneously visible ho sakte hain.

---

## No New Files

Sirf existing files modify kiye gaye hain. No new layout, no new drawable, no new Activity.
