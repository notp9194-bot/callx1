# Call System Fixes — v2 (Fixes 1–6 added below v1 fixes)

---
<!-- ═══════════════ v1 fixes below (unchanged) ═══════════════ -->

## Fix 1: partnerThumb missing in ChatActivity.startCall()

**File:** `feature-chat/.../ChatActivity.java`

**Problem:** ChatActivity `startCall()` `partnerThumb` (100×100 WebP thumbnail) pass nahi karta tha
CallActivity ko. CallActivity `partnerThumb` prefer karta hai fast avatar load ke liye — lekin
ye null aa raha tha, fallback full `partnerPhoto` se load hota tha (slow).

**Fix:** `startCall()` me `partnerThumb` extra add kiya:
```java
i.putExtra("partnerThumb", partnerThumb);
```

---

## Fix 2: Missed Call Notification — System Hi Nahi Tha

**Files changed:**
- `core/.../PushNotify.java` — `notifyMissedCall()` + `notifyMissedGroupCall()` methods added
- `feature-calls/.../NotificationActionReceiver.java` — Decline action pe missed call push
- `feature-calls/.../CallActivity.java` — watchCallStatus() me rejected/cancelled pe push
- `app/.../CallxMessagingService.java` — `"missed_call"` + `"missed_group_call"` FCM types handle kiye

**Problem:** Agar callee notification se decline kare ya ignore kare, caller ko koi "Missed Call"
notification nahi jaati thi. `PushNotify.notifyMissedCall()` exist hi nahi karta tha.

**Fix:**
1. `PushNotify.notifyMissedCall(toUid, fromUid, fromName, callId, isVideo)` add kiya
2. `PushNotify.notifyMissedGroupCall(groupId, ...)` add kiya
3. `NotificationActionReceiver.ACTION_DECLINE_CALL` me notification bhejo caller ko
4. `CallActivity.watchCallStatus()` me rejected/cancelled pe (pre-connect) notification bhejo
5. FCM: `"missed_call"` aur `"missed_group_call"` types route kiye existing handlers me
6. `showMissedCallNotification()` me `fromUid/fromName` field names ka fallback add kiya

---

## Fix 3: GroupCallActivity — eglBase Null When Adapter Created

**Files changed:**
- `feature-calls/.../GroupCallParticipantAdapter.java` — `eglBase` non-final, `setEglBase()` added
- `feature-calls/.../GroupCallActivity.java` — `initWebRTCAndJoin()` me `adapter.setEglBase()` call

**Problem:** `GroupCallParticipantAdapter` ko `onCreate()` me banaya jaata tha jab `eglBase = null`.
`eglBase` tab create hota hai `initWebRTCAndJoin()` me (TURN fetch ke baad, async). Result:
video tiles kabhi render nahi hoti thi kyunki `eglBase != null` check fail hota tha `onBindViewHolder` me.

Bonus bug: `onViewRecycled()` bina check ke `renderer.release()` call karta tha — crash hota tha
agar ViewHolder kabhi bind hi nahi hua ho (renderer uninitialized).

**Fix:**
1. `eglBase` field ko `final` se `private` kiya
2. `setEglBase(EglBase)` method add kiya adapter me
3. `initWebRTCAndJoin()` me `eglBase = EglBase.create()` ke turant baad `adapter.setEglBase(eglBase)` call kiya
4. `VH` me `rendererInitialized` boolean flag add kiya
5. `onBindViewHolder`: release + re-init karo agar already initialized
6. `onViewRecycled`: sirf release karo agar `rendererInitialized == true`


---

# Call System Fixes — v2 (6 New Fixes)

---

## Fix 1: partnerThumb callee path mein null tha

**Files changed:**
- `app/.../CallxMessagingService.java` — `fromThumb` FCM data se read karke `ringIntent` + `acceptIntent` + `declineIntent` mein pass kiya
- `feature-calls/.../IncomingRingService.java` — `fromPhoto` + `fromThumb` ko `onStartCommand` mein read kiya; `buildNotification()` signature update; `fullIntent` + `declineIntent` mein forward kiya
- `feature-calls/.../IncomingCallActivity.java` — `fromThumb` field add kiya; intent se read kiya; `accept()` mein `partnerThumb` extra `CallActivity` ko pass kiya

**Problem:** FIX-1 (v1) sirf `ChatActivity.startCall()` caller path ke liye tha. Callee path (`FCM → IncomingRingService → IncomingCallActivity → CallActivity`) mein `partnerThumb` kabhi pass hi nahi hota tha — `CallActivity` pe null aata tha, slow full-photo fallback chalta tha.

---

## Fix 2: Notification Action Accept/Decline pe photo/thumb nahi jaata tha IncomingCallActivity ko

**Files changed:**
- `app/.../CallxMessagingService.java` — `acceptIntent` aur `declineIntent` mein `EXTRA_PARTNER_PHOTO` + `partnerThumb` add kiye
- `feature-calls/.../IncomingRingService.java` — `declineIntent` mein bhi photo + thumb forward kiye

**Problem:** Agar user notification ke Accept ya Decline button se tap kare (full-screen UI ki jagah), `IncomingCallActivity` ko photo/thumb milta hi nahi tha — avatar blank dikhta tha.

---

## Fix 3: Call Back action hamesha audio call karta tha, video missed call pe bhi

**Files changed:**
- `app/.../CallxMessagingService.java` — `showMissedCallNotification()` mein `missedIsVideo` FCM payload se read kiya; `callBackIntent` mein `EXTRA_IS_VIDEO` + `EXTRA_PARTNER_PHOTO` pass kiye
- `feature-calls/.../NotificationActionReceiver.java` — `ACTION_CALL_BACK` mein `cbIsVideo` intent se padhta hai (hardcoded `false` remove), `partnerPhoto` + `isCaller=true` bhi pass karta hai

**Problem:** `callBackIntent` mein `EXTRA_IS_VIDEO` hardcoded `false` tha. Video call miss hone par bhi callback hamesha audio hoti thi.

---

## Fix 4: Group call mein caller avatar blank tha

**Files changed:**
- `core/.../Constants.java` — `EXTRA_GROUP_CALLER_PHOTO` constant add kiya
- `app/.../CallxMessagingService.java` — `showIncomingGroupCall()` mein `callerPhoto` (`GCALL_FCM_CALLER_PHOTO`) read kiya; `ringIntent` mein pass kiya
- `feature-calls/.../GroupCallRingService.java` — `callerPhoto` read kiya; `buildRingNotification()` signature update; `fullIntent` (IncomingGroupCallActivity) mein forward kiya
- `feature-calls/.../IncomingGroupCallActivity.java` — `EXTRA_CALLER_PHOTO` constant add kiya; `callerPhoto` field add kiya; Glide se `ivIncomingGroupCallerAvatar` load kiya

**Problem:** Group call FCM mein `callerPhoto` available tha lekin kisi bhi intent mein pass nahi ho raha tha — `IncomingGroupCallActivity` mein caller avatar hamesha blank tha.

---

## Fix 5: ICE restart sirf caller karta tha — callee passive rehta tha

**Files changed:**
- `feature-calls/.../CallActivity.java` — `scheduleIceRestart()` mein callee path add kiya: callee Firebase `iceRestartRequest` node mein timestamp likhta hai; caller `watchCalleeIceRestartRequest()` se yeh sun ke ICE restart trigger karta hai; `releaseWebRTC()` mein listener cleanup

**Problem:** `scheduleIceRestart()` mein sirf `if (isCaller)` condition thi — callee ka connection drop hone par callee kuch nahi kar sakta tha. Caller ko khud pata nahi chalta tha ki callee reconnect chahta hai.

---

## Fix 6: Ongoing call notification mein caller avatar nahi dikhta tha

**Files changed:**
- `feature-calls/.../CallActivity.java` — `onCallConnected()` mein `CallForegroundService` intent mein `partnerThumb` extra pass kiya
- `feature-calls/.../CallForegroundService.java` — `partnerThumb` read kiya; background thread pe avatar download kiya; `buildNotification()` mein `b.setLargeIcon(avatarBitmap)` set kiya; Android 12+ `Person.Builder` mein `setIcon(IconCompat.createWithBitmap(...))` add kiya; `bgEx` executor cleanup `onDestroy()` mein

**Problem:** `CallForegroundService` ko `partnerThumb` URL pass hi nahi hoti thi — ongoing call notification sirf text dikhati thi, koi avatar nahi. Android 12+ call chip pe caller face blank tha.

---

## Changed Files Summary

| File | Fix(es) |
|------|---------|
| `core/.../Constants.java` | 4 |
| `app/.../CallxMessagingService.java` | 1, 2, 3, 4 |
| `feature-calls/.../IncomingRingService.java` | 1, 2 |
| `feature-calls/.../IncomingCallActivity.java` | 1 |
| `feature-calls/.../GroupCallRingService.java` | 4 |
| `feature-calls/.../IncomingGroupCallActivity.java` | 4 |
| `feature-calls/.../NotificationActionReceiver.java` | 3 |
| `feature-calls/.../CallActivity.java` | 5, 6 |
| `feature-calls/.../CallForegroundService.java` | 6 |

