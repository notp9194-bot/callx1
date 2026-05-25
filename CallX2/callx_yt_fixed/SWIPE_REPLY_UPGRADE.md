# CallX2 — Swipe-to-Reply System Upgrade

**Version:** SwipeReplySystem v1  
**Target SDK:** Android 24+  
**Architecture:** WhatsApp/Telegram-grade, zero-compromise implementation

---

## What Was Upgraded

### New Java Modules (9 files)

| File | Package | Purpose |
|------|---------|---------|
| `SwipeReplyHandler.java` | `chat.gesture` | ItemTouchHelper.Callback, rubber-band physics, haptics, icon animation |
| `ReplyController.java` | `chat.reply` | State machine: IDLE → PENDING_UNDO → ACTIVE, 2s undo window |
| `ReplyStateManager.java` | `chat.reply` | Single source of truth for active reply message |
| `ReplyDataMapper.java` | `chat.reply` | Maps reply fields onto outgoing messages, sanitizes incoming |
| `ReplyBarView.java` | `chat.ui` | Animated slide-up reply bar custom view |
| `MessageHighlightAnimator.java` | `chat.ui` | Scroll-to and yellow flash animation for original messages |
| `SwipeOptimizer.java` | `chat.performance` | translationX-only path, DynamicAnimation spring-back, no layout passes |
| `ReplyAnalyticsTracker.java` | `chat.analytics` | In-memory analytics: attempts, success rate, undo count |

### Modified Existing Files

| File | Changes |
|------|---------|
| `Message.java` | Added `replyToType`, `replyToMediaUrl` fields |
| `MessageEntity.java` | Added `replyToType`, `replyToMediaUrl` DB columns |
| `ChatActivity.java` | Integrated SwipeReplyHandler, ReplyController, undo snackbar, `navigateToOriginal()`, `setupFabBackToLatest()` |
| `MessagePagingAdapter.java` | Added `llReplyPreview`, `tvReplySender`, `tvReplyText`, `ivReplyThumb` to VH; full reply preview binding with Glide thumbnail + click navigation |

### New Layouts

| File | Changes |
|------|---------|
| `activity_chat.xml` | FrameLayout wraps RecyclerView + FAB; FAB `fab_back_to_latest` added; reply bar upgraded with `iv_reply_bar_thumb` + slide-up animation ready |
| `item_message_received.xml` | Fully rewritten: left border on reply preview, `iv_reply_thumb`, clickable `ll_reply_preview`, all original fields preserved |
| `item_message_sent.xml` | Fully rewritten: gold left border on reply preview, `iv_reply_thumb`, clickable `ll_reply_preview`, all original fields preserved |

### New Drawables

| File | Purpose |
|------|---------|
| `ic_reply.xml` | Vector reply icon shown during swipe gesture |
| `bg_reply_bar.xml` | Light blue background for reply bar |
| `bg_fab_back.xml` | Blue oval for back-to-latest FAB |
| `bg_reply_preview_received.xml` | Semi-transparent dark bg for received reply preview |
| `bg_reply_preview_sent.xml` | Semi-transparent dark bg for sent reply preview |

---

## Integration Architecture

```
Swipe gesture
     │
     ▼
SwipeReplyHandler (ItemTouchHelper.Callback)
  • rubberBand(dx) physics
  • 18% width trigger threshold
  • drawSwipeTint() + drawReplyIcon()
  • Haptic: keyboard_press at threshold, long_press at trigger
     │
     ▼  onSwipeReply(message)
ReplyController
  • State: IDLE → PENDING_UNDO (2s snackbar) → ACTIVE
  • Undo: Snackbar "Replying to {name}…" with UNDO action
     │
     ├─[Undo pressed]─► IDLE (no reply bar shown)
     │
     ▼  onReplyActivated(message)
ChatActivity.activateReplyDirect(message)
  • Populates reply bar (name + preview + thumbnail)
  • Slide-up animation (200ms FastOutSlowIn)
     │
     ▼  send pressed
ReplyDataMapper.applyReplyFields(outgoing, replyingTo)
  • Copies replyToId, replyToSenderName, replyToText
  • Sets replyToType, replyToMediaUrl (for thumbnail on receiver)
     │
     ▼
Firebase → Room → PagingSource → RecyclerView
     │
     ▼  User taps reply preview in bubble
MessagePagingAdapter.llReplyPreview.onClick
     │
     ▼
ChatActivity.navigateToOriginal(messageId)
     │
     ├─[found in current page]─► MessageHighlightAnimator.scrollAndHighlight()
     │                           • smoothScrollToPosition()
     │                           • yellow flash (1.5s fade)
     │                           • FAB fab_back_to_latest shown
     │
     └─[not in page]─► Toast "scroll up to find it"
```

---

## Swipe Gesture Physics

| Parameter | Value | Effect |
|-----------|-------|--------|
| Trigger threshold | 18% of item width (min 72dp) | Feel: feels intentional, not accidental |
| Max drag | 30% of item width | Clamp: prevents bubble from going off-screen |
| Resistance formula | `x * (1 - x / (3 * max))` | Rubber-band: decelerates naturally |
| Spring-back stiffness | `STIFFNESS_MEDIUM` | Snappy but not jarring |
| Spring damping ratio | `0.7f` | Slight undershoot for feel |
| Haptic at threshold | `KEYBOARD_PRESS` | Subtle: "you're in the zone" |
| Haptic at trigger | `LONG_PRESS` | Strong: "reply activated" |
| Debounce | 350ms | Prevents accidental double-trigger |
| Direction sent | LEFT swipe | Natural: sent bubbles on right |
| Direction received | RIGHT swipe | Natural: received bubbles on left |

---

## DB Schema Change

Two new columns added to `messages` table:

```sql
ALTER TABLE messages ADD COLUMN replyToType TEXT;
ALTER TABLE messages ADD COLUMN replyToMediaUrl TEXT;
```

**Action required:** Increment `AppDatabase` version by 1 and add a migration:

```java
static final Migration MIGRATION_X_Y = new Migration(X, Y) {
    @Override public void migrate(SupportSQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToType TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN replyToMediaUrl TEXT");
    }
};
```

---

## Feature Flags

All controllable at runtime from `SwipeReplyHandler` and `ReplyController`:

```java
SwipeReplyHandler.ENABLE_SWIPE_REPLY = true;  // master kill-switch
SwipeReplyHandler.ENABLE_HAPTICS     = true;  // haptic feedback
ReplyController.ENABLE_UNDO          = true;  // 2s undo snackbar
```

---

## Performance Notes

- **Zero layout passes during swipe** — only `translationX` is changed
- **Hardware layer** not forced (avoids GPU overdraw for short swipes)
- **DynamicAnimation** spring-back is fully hardware-accelerated
- **No object allocation** in `onChildDraw()` hot path (Paint reused)
- **RecyclerView change animations disabled** via `SwipeOptimizer.disableChangeAnimations()`

