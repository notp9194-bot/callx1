# Incoming Call Screen — Fixes & Upgrades

## Files Changed
1. `feature-calls/src/main/res/layout/activity_incoming_call.xml`
2. `app/src/main/res/layout/activity_incoming_call.xml` (synced)
3. `feature-calls/src/main/res/drawable/bg_ripple_ring.xml` (NEW)
4. `feature-calls/src/main/java/com/callx/app/incoming/IncomingCallActivity.java`
5. `feature-calls/src/main/java/com/callx/app/history/CallHistoryAdapter.java`

---

## What Was Fixed

### 1. Ripple Ring Animation (Layout + Java)
- 3 concentric ring views added behind avatar
- New drawable `bg_ripple_ring.xml` — white oval stroke
- Each ring pulses with offset (0ms, 300ms, 600ms) for wave effect
- Scale + alpha animated simultaneously

### 2. Quick Reply Buttons (NEW FEATURE)
- 3 pre-set reply chips: "Can't talk", "Call later", "On my way"
- Tapping any chip: rejects the call + sends message to caller via Firebase chat
- Shows Toast confirmation

### 3. "Ringing..." Animated Dots
- `tv_ringing_status` shows "Ringing", "Ringing.", "Ringing..", "Ringing..."
- Cycles every 500ms until call is acted on

### 4. Layout: LinearLayout → ConstraintLayout
- Full ConstraintLayout — proper positioning on all screen sizes
- Avatar centered vertically at 38% (matches WhatsApp proportion)
- Badge uses rounded semi-transparent background
- Avatar has white border via `civ_border_*`

### 5. Missed Call Direction Bug (FIXED)
- **Before**: Caller's call log wrote `direction = "missed"` — WRONG
- **After**: Caller's call log writes `direction = "no_answer"` — correct
- `CallHistoryAdapter` now shows "No Answer" in orange for `no_answer` direction

### 6. All Cleanup Methods Updated
- `stopRippleAnimation()` and `stopRingingDots()` called in `accept()`, `reject()`, `sendQuickReply()`, `onDestroy()`
