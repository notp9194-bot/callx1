# CallX — Duet System Upgrade v6 (5 Critical Fixes)

## Files Changed

| File | Change |
|---|---|
| `feature-reels/.../DuetsByReelActivity.java` | Fix #1 — Duet player integration |
| `feature-reels/.../ReelPlayerFragment.java` | Fix #2, #3, #4, #5 — Badge, permissions, stitch |
| `feature-reels/.../DuetNotificationWorker.java` | Fix #3 — FCM push notification |
| `feature-reels/.../DuetReelActivity.java` | Rich notification (photo + thumb) |

---

## Fix #1 — DuetsByReelActivity: Player Integration

**Problem:** Tapping a duet in the grid showed a Toast ("Opening duet by @...") — actual reel never opened. There was a `// TODO:` comment there for months.

**Fix:** `onDuetTapped()` now opens `SingleReelPlayerActivity` with `EXTRA_REEL_ID`:
```java
Intent i = new Intent(this, SingleReelPlayerActivity.class);
i.putExtra(SingleReelPlayerActivity.EXTRA_REEL_ID, reel.reelId);
i.putExtra(SingleReelPlayerActivity.EXTRA_TITLE, "Duet by @" + reel.ownerName);
startActivity(i);
```

---

## Fix #2 — ReelPlayerFragment: Duet Badge in Feed

**Problem:** Duet reels looked identical to regular reels — user couldn't tell if a reel was a duet.

**Fix:** Caption now shows "🔀 Duet · " prefix when `reel.duetOf` is non-empty.

Also added duet fields to `newInstance()` Bundle so they survive Fragment recreation:
- `duet_of`, `duet_count`, `allow_duet_level`, `allow_stitch_level`

---

## Fix #3 — FCM Push Notification (DuetNotificationWorker)

**Problem:** Worker only wrote to Firebase RTDB. Original creator's device never received an actual push notification.

**Fix (2 steps):**

**Step 1 — RTDB write (same as before)**

**Step 2 — FCM push (NEW):**
1. Reads owner's FCM token from `users/{ownerUid}/fcmToken`
2. POSTs to `https://fcm.googleapis.com/fcm/send` with:
   - `notification.title`: "Sender dueted your reel 🔀"
   - `notification.image`: reel thumbnail URL
   - `data`: type, reel_id, from_uid, from_name, from_photo, owner_uid

**Setup required:** Add to `app/src/main/res/values/strings.xml`:
```xml
<string name="fcm_server_key">YOUR_SERVER_KEY_FROM_FIREBASE_CONSOLE</string>
```
*(Firebase Console → Project Settings → Cloud Messaging → Server Key)*

Also make sure your app saves FCM token on login:
```java
FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token ->
    FirebaseUtils.db().getReference("users").child(uid).child("fcmToken").setValue(token));
```

---

## Fix #4 — "Followers Only" Duet Permission Enforced

**Problem:** `allowDuetLevel = "followers"` had no follower check. Any stranger could duet.

**Fix:** `openDuet()` in `ReelPlayerFragment` now:
1. Checks `allowDuetLevel` string ("everyone" / "followers" / "off")
2. If "followers" AND `!isFollowing` → shows "Only followers can duet this reel" Toast and returns
3. Passes `EXTRA_ALLOW_DUET_LEVEL` + `EXTRA_VIEWER_FOLLOWS` to `DuetReelActivity` (double enforcement)

Same fix applied to `openStitch()` using `allowStitchLevel`.

---

## Fix #5 — allowStitch Read from ReelModel

**Problem:** `showMoreOptions()` had `boolean allowStitch = true; // can add allowStitch to ReelModel later` — hardcoded, ignored creator's setting.

**Fix:** Now uses `reel.allowStitchLevel`:
```java
boolean allowStitch = !"off".equals(reel.allowStitchLevel);
```
`openStitch()` also enforces "followers" level with `!isFollowing` check.

---

## Integration Checklist

- [ ] Add `fcm_server_key` to strings.xml
- [ ] Save FCM token in `users/{uid}/fcmToken` on app login
- [ ] `ReelModel.duetOf`, `allowDuetLevel`, `allowStitchLevel` fields must be saved to Firebase on upload (already in model)
- [ ] Test: Create a reel with duet=Off → verify duet button grayed in feed
- [ ] Test: Create a reel with duet=Followers → verify stranger cannot duet, follower can
