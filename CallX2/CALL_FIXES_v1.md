# Call System Fixes — v1

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

