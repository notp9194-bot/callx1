# CallX — Call System Fixes v3

Yeh document un **7 bugs aur missing features** ki complete list hai jo is update mein fix kiye gaye hain.

---

## 🔴 Bug Fix 1 (Critical): Ongoing Call Notification Tap → App Crash / Blank Screen

**Files changed:**
- `feature-calls/.../CallForegroundService.java`
- `feature-calls/.../CallActivity.java`

**Problem:**
`CallForegroundService.buildNotification()` mein `openIntent` koi extras pass nahi karta tha `CallActivity` ko.
`CallActivity.onCreate()` mein check hai: `if (partnerUid == null) { finish(); return; }`.
Matlab — active call ke dauran agar notification pe tap karo, `CallActivity` turant band ho jaati thi.
User call screen par wapas nahi aa sakta tha.

**Fix — CallForegroundService.java:**
```java
// Pehle (broken):
Intent openIntent = new Intent(this, CallActivity.class);
// koi extras nahi → partnerUid == null → finish()

// Ab (fixed):
Intent openIntent = new Intent(this, CallActivity.class);
openIntent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_REORDER_TO_FRONT);
openIntent.putExtra("partnerUid",   partnerUid);
openIntent.putExtra("partnerName",  callerName);
openIntent.putExtra("partnerPhoto", partnerPhoto);
openIntent.putExtra("partnerThumb", partnerThumb);
openIntent.putExtra("callId",       callId);
openIntent.putExtra("video",        isVideo);
openIntent.putExtra("isCaller",     isCaller);
openIntent.putExtra("isRestore",    true);  // re-init skip karo
```

**Fix — CallForegroundService.java `onStartCommand()`:**
- `EXTRA_PARTNER_PHOTO` aur `EXTRA_IS_CALLER` constants add kiye
- `partnerPhoto` aur `isCaller` store kiye onStartCommand mein
- `onCallConnected()` mein in dono extras pass kiye ForegroundService ko

---

## 🔴 Bug Fix 2: `partnerPhoto` CallForegroundService ko nahi milti thi

**Files changed:**
- `feature-calls/.../CallActivity.java` — `onCallConnected()`
- `feature-calls/.../CallForegroundService.java` — `onStartCommand()`

**Problem:**
`onCallConnected()` mein `partnerPhoto` `CallForegroundService` ko pass nahi hoti thi.
Isliye Bug #1 fix ke liye `openIntent` mein `partnerPhoto` blank tha.

**Fix:**
```java
fg.putExtra(CallForegroundService.EXTRA_PARTNER_PHOTO, partnerPhoto != null ? partnerPhoto : "");
fg.putExtra(CallForegroundService.EXTRA_IS_CALLER,     isCaller);
```

---

## 🟡 Feature Fix 3: Headphone Unplug → Audio Earpiece Pe Nahi Aata Tha

**Files changed:**
- `feature-calls/.../CallActivity.java`

**Problem:**
`AudioManager.ACTION_AUDIO_BECOMING_NOISY` broadcast receiver nahi tha.
Jab headphones nikale jaate, audio speaker pe blast hota tha — WhatsApp style auto-earpiece switch nahi tha.

**Fix — CallActivity.java:**
```java
// registerNoisyReceiver() add kiya — WebRTC init ke baad call hota hai
noisyReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
            if (speakerOn) enableSpeaker(false);  // earpiece pe wapas
        }
    }
};
registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
```
- `releaseWebRTC()` mein `unregisterReceiver()` bhi add kiya

---

## 🟡 Feature Fix 4: Bluetooth Headset Support (1-to-1 Call)

**Files changed:**
- `feature-calls/.../CallActivity.java`

**Problem:**
`GroupCallActivity` mein Bluetooth SCO support tha lekin `CallActivity` (1-to-1) mein bilkul nahi tha.
Bluetooth earphones connect karne par audio routing automatic nahi hoti thi.

**Fix — CallActivity.java:**
```java
// registerBtScoReceiver() add kiya
btScoReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
        int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            audioManager.setBluetoothScoOn(true);
            speakerOn = false;
        } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            enableSpeaker(false);  // earpiece fallback
        }
    }
};
registerReceiver(btScoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
```
- `releaseWebRTC()` mein cleanup bhi add kiya

---

## 🟡 Feature Fix 5: Local Video PiP Box Drag Nahi Hoti Thi

**Files changed:**
- `feature-calls/.../CallActivity.java`

**Problem:**
Local video preview (PiP thumbnail) top-right corner pe fixed tha.
Koi `OnTouchListener` nahi tha — user isko move nahi kar sakta tha.

**Fix — CallActivity.java:**
```java
// setupLocalVideoDrag() add kiya — onCreate() mein call hota hai
binding.localVideo.setOnTouchListener((v, event) -> {
    switch (event.getAction()) {
        case ACTION_DOWN: dX = v.getX() - event.getRawX(); ...
        case ACTION_MOVE:
            newX = clamp(event.getRawX() + dX, 0, parentWidth - v.getWidth());
            newY = clamp(event.getRawY() + dY, 0, parentHeight - v.getHeight());
            v.setX(newX); v.setY(newY);
            // cam-off badge bhi saath move hoti hai
            binding.layoutLocalCamOffBadge.setX(newX);
            binding.layoutLocalCamOffBadge.setY(newY);
    }
});
```

---

## 🟡 Feature Fix 6: Network Quality "Poor" State Missing Tha

**Files changed:**
- `feature-calls/.../CallActivity.java` — `showQualityIndicator()`

**Problem:**
`showQualityIndicator()` sirf `CONNECTED`/`COMPLETED` (Good) aur `DISCONNECTED` (Weak) handle karta tha.
`FAILED` aur `CHECKING` states ke liye koi indicator nahi tha — indicator ghayab ho jaata tha.

**Fix:**
```java
} else if (state == IceConnectionState.FAILED ||
           state == IceConnectionState.CHECKING) {
    // FIX: "Poor" state — pehle yeh case missing tha
    binding.layoutQuality.setVisibility(View.VISIBLE);
    binding.tvQualityLabel.setText("Poor");
}
```

---

## 🟡 Feature Fix 7: `camState` Firebase Node Call End Pe Cleanup Nahi Hota Tha

**Files changed:**
- `feature-calls/.../CallActivity.java` — `endCall()`

**Problem:**
`toggleCamera()` Firebase mein `camState/{myUid}` likhta tha lekin `endCall()` isko delete nahi karta tha.
Har call ke baad Firebase mein garbage data rehta tha.

**Fix — endCall() mein:**
```java
// FIX-CLEANUP: Call khatam hone par apna camState node hata do
if (isVideo) {
    String myUid = FirebaseUtils.getCurrentUid();
    if (myUid != null)
        callRef.child("camState").child(myUid).removeValue();
}
```

---

## Changed Files Summary

| File | Fix(es) Applied |
|------|----------------|
| `feature-calls/.../CallForegroundService.java` | 1, 2 |
| `feature-calls/.../CallActivity.java` | 1, 2, 3, 4, 5, 6, 7 |

---

## Kya Nahi Badla (Already Implement Tha)

| Feature | Status |
|---------|--------|
| App kill hone par Firebase "ended" | ✅ `onTaskRemoved()` |
| Firebase `onDisconnect()` | ✅ |
| Background mein camera pause | ✅ `onStop()` |
| PiP mode (Home press par) | ✅ |
| Remote camera off overlay | ✅ |
| ICE restart caller+callee | ✅ |
| GroupCallActivity ICE restart | ✅ (already implemented) |
| Missed call notification | ✅ |
| Ongoing notification avatar | ✅ |
| Screen wake lock | ✅ |

---

*CallX v3 Call Fixes — June 2026*
