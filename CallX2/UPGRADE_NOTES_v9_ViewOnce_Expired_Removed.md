# CallX2 — ViewOnce Feature Upgrade: Features 7 & 8

**Release:** v9_ViewOnce_Expired_Removed  
**Base:** v4 (ViewOnce_v4 zip)  
**Date:** June 2026

---

## Summary

Two new states added to the View Once system:

| # | Feature | State | Label shown to receiver |
|---|---------|-------|------------------------|
| 7 | Expiry timer runs out before receiver opens | `expired` | **"Expired"** |
| 8 | Sender revokes before receiver opens | `revoked` | **"Removed"** |

Content is **permanently deleted** from Firebase in both cases. The node is kept as a tombstone with the new state so both sides render the correct bubble.

---

## Files Changed

### 1. `ChatViewOnceController.java`

**New state constants:**
```java
public static final String STATE_EXPIRED = "expired";
public static final String STATE_REVOKED = "revoked";
public static final String FIELD_VIEW_ONCE_EXPIRES_AT = "viewOnceExpiresAt";
```

**New helper methods:**
- `isTimerExpired(Message m)` — returns `true` if `viewOnceState == "expired"`
- `isRevoked(Message m)` — returns `true` if `viewOnceState == "revoked"`

**New public API:**

```java
// Feature 7 — call from WorkManager/alarm when System.currentTimeMillis() >= viewOnceExpiresAt
controller.markExpiredByTimer(messageId);

// Feature 8 — call from long-press confirm dialog on sender's lock bubble
controller.revokeViewOnce(messageId, onSuccess, onFailure);
```

Both methods:
- Set `viewOnceState` to `"expired"` / `"revoked"`
- Set `deleted = true`
- Wipe `text`, `mediaUrl`, `thumbnailUrl`, `fileName` from Firebase
- Call `softDeleteLocally()` immediately for optimistic UI

**Updated `isExpired()`:**  
Now also returns `true` for `STATE_EXPIRED` and `STATE_REVOKED`, so the adapter correctly routes these to `TYPE_VIEW_ONCE_EXPIRED` view type.

**Updated `openViewOnce()` guard:**  
Prevents receiver from tapping an already-revoked or already-expired bubble.

---

### 2. `MessagePagingAdapter.java` — `bindViewOnceExpired()`

`tv_expired_label` now shows:

| `viewOnceState` | Label |
|-----------------|-------|
| `opened` / `deleted` | `"Opened"` |
| `expired` | `"Expired"` |
| `revoked` | `"Removed"` |

`tv_opened_at` ("Opened · Jun 28, 3:45 PM") is hidden for `expired` and `revoked` states — there is no `openedAt` timestamp in those flows.

---

### 3. `item_view_once_expired.xml`

Comment updated to document all three label states. No XML structural change needed — `tv_expired_label` text is set dynamically by the adapter.

---

## Integration Steps

### Feature 7 — Expiry Timer

When sender picks a duration (1hr / 6hr / 24hr / 3d / 7d), save it:

```java
// In ChatActivity / send flow:
long expiresAt = System.currentTimeMillis() + selectedDurationMs;
message.viewOnceExpiresAt = expiresAt;
// Firebase write already handled by ChatMessageSender
```

Schedule a WorkManager OneTimeWorkRequest with a delay equal to `selectedDurationMs`. In the Worker's `doWork()`:

```java
// Fetch message from Firebase; check viewOnceState == "sent" before marking
if (STATE_SENT.equals(currentState)) {
    controller.markExpiredByTimer(messageId);
}
```

### Feature 8 — Revoke (Long-press lock bubble)

In `ChatActivity` / sender's long-press handler on `TYPE_VIEW_ONCE_SENT_WAITING` bubble:

```java
new AlertDialog.Builder(this)
    .setTitle("Remove message?")
    .setMessage("This will permanently delete the message before it's opened.")
    .setPositiveButton("Remove", (d, w) -> {
        viewOnceController.revokeViewOnce(
            message.messageId,
            () -> Toast.makeText(this, "Message removed", Toast.LENGTH_SHORT).show(),
            () -> Toast.makeText(this, "Failed, try again", Toast.LENGTH_SHORT).show()
        );
    })
    .setNegativeButton("Cancel", null)
    .show();
```

---

## Firebase State Machine (Updated)

```
SENT ─────────────────────────────────── receiver taps → OPENED → DELETED
  │
  ├── viewOnceExpiresAt passed → markExpiredByTimer() → EXPIRED
  │
  └── sender long-presses → revokeViewOnce() → REVOKED
```

All three terminal states permanently wipe content from Firebase.

---

## No New Layouts Required

The existing `item_view_once_expired.xml` bubble is reused for all three post-open states. Only the label text changes.
