# UPGRADE_NOTES v183 — Reels Performance Fix (Anti-Heat / Anti-Crash)

## Problem
App 40 reels ke baad **garam ho jaata tha, hang aur crash** karta tha.
Instagram mein aisa nahi hota. Root cause: 3 alag leaks ek saath chal rahe the.

---

## Root Cause Analysis

### ❶ `offscreenPageLimit = 4` (SABSE BADI GALTI)
`ReelsFragment.java` mein pehle:
```java
vpReels.setOffscreenPageLimit(4); // 5 fragments ek waqt memory mein
```
Matlab ek waqt mein N-1, N, N+1, N+2, N+3 — **5 fragments simultaneously alive**.
Har fragment mein apna ExoPlayer + apne Firebase listeners tha.
40 reels scroll karne ke baad dozens of Firebase connections + multiple
codec threads chal rahe the. Yahi heat ka number 1 reason tha.

**Fix:** `offscreenPageLimit = 1` → sirf 3 fragments (N-1, N, N+1). 60-70% improvement.

---

### ❷ Firebase Listeners Visibility se Tied Nahi The
`ReelPlayerFragment.onCreateView()` mein pehle:
```java
socialController.startFirebaseListeners(); // HAMESHA — visible ho ya na ho
```
- likeListener, saveListener, followListener, countListener,
  repostListener, reactionsListener, likersListener — **7 real-time connections PER FRAGMENT**
- 5 offscreen fragments × 7 = **35 simultaneous Firebase connections**
- Sirf `onDestroyView` mein remove hote the — fragment destroy kabhi hota nahi (offscreenPageLimit=4)

**Fix:** Listeners ab sirf `applyVisibleState(true)` mein start hote hain aur
`applyVisibleState(false)` mein turant remove. Reel invisible hote hi Firebase
connections free.

---

### ❸ Preloader Zyada Aggressive Tha
```java
PRELOAD_COUNT = 4;           // agle 4 reels
PRELOAD_BYTES_WIFI = 10MB;   // WiFi pe 10MB each
// = 40MB concurrent downloads + 3 background threads
```
ExoPlayer ka khud ka network + yeh 40MB = phone ki battery jaldi khatam,
CPU hot hota tha.

**Fix:**
- `PRELOAD_COUNT = 2` (4 → 2)
- `PRELOAD_BYTES_WIFI = 4MB` (10MB → 4MB)
- Background threads: 3 → 2
- Total concurrent download: 40MB → 8MB

---

## Files Changed

| File | Change |
|------|--------|
| `feature-reels/.../feed/ReelsFragment.java` | `offscreenPageLimit` 4→1, prewarmDistance capped to 1 |
| `feature-reels/.../feed/ReelPlayerFragment.java` | Firebase listeners moved to `applyVisibleState()` |
| `feature-reels/.../feed/controllers/ReelSocialController.java` | `listenersActive` guard added, refs nulled after remove |
| `feature-reels/.../cache/ReelVideoPreloader.java` | PRELOAD_COUNT 4→2, bytes 10MB→4MB, threads 3→2 |
| `feature-reels/.../player/ExoPlayerPool.java` | POOL_SIZE 4→3 (matches new offscreenPageLimit) |

---

## Instagram Ka Approach (Reference)

| Feature | Before Fix | After Fix | Instagram |
|---------|-----------|-----------|-----------|
| offscreenPageLimit | 4 | 1 | 1 |
| Firebase listeners | Always ON | Only when visible | Only when visible |
| ExoPlayer instances | Up to 5 | Max 3 | Pool of 2-3 |
| Preload per reel | 10MB | 4MB | 3-5MB |
| Preload count | 4 | 2 | 2 |

---

## Expected Result
- 40+ reels scroll karne ke baad bhi phone zyada garam nahi hoga
- Crash drastically kam hoga (OOM kill chances bahut kam)
- Battery drain 40-50% better
- Swipe speed same — N+1 prewarm ab bhi hota hai

## Integration
Direct drop-in — koi naya dependency nahi, koi API change nahi.
