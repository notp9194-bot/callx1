# v82 — Chat List & Group List: Full Canvas Row

## What Changed

Both `item_chat.xml` (1:1 chat list) and `item_group.xml` (group list) are now
fully canvas-rendered — every non-avatar, non-overlay View in the row either
already was canvas (v23/v24: `ChatListLastMessageView`) or has been converted
in this version:

### New Canvas Views (all in `chatlist/canvas/`)

| New class | Replaces | Per-bind saving |
|---|---|---|
| `ChatListNameTimeView` | `tv_name` (TextView) + `tv_time` (TextView) inside a horizontal LinearLayout | 2 TextView measure/layout passes → 1 canvas drawText per side |
| `ChatListUnreadBadgeView` | `tv_unread_badge` (TextView + `@drawable/bg_unread_badge` GradientDrawable) | GradientDrawable inflate + fill draw + TextView draw → 1 drawRoundRect + 1 drawText; self-measures to 0×0 when count=0, so no GONE/VISIBLE toggle |
| `ChatListStoryRingView` | `iv_story_ring` (ImageView with background swap between two GradientDrawable XMLs) | Two drawable inflations + full Drawable draw → 1 drawOval STROKE call; `STATE_NONE` skips draw entirely |
| `ChatListCallButtonsView` | `ll_call_btns` (LinearLayout) + two `ImageButton` widgets with tinted VectorDrawables | 2 ImageButton measure/layout passes + 2 VectorDrawable draws → 1 canvas Path draw; touch regions split at midpoint without nested clickable children |

### Layout changes

- **CardView root** → plain `FrameLayout` with `@drawable/bg_chat_row`
  (`bg_chat_row.xml` is a `<shape>` with the same 16dp corner radius and
  `@color/surface_card` fill). `cardElevation` was already `0dp`, so there is
  no visible change — but CardView's extra measure/layout pass is gone.

### What was deliberately NOT changed

- `CircleImageView` (`iv_avatar`, `iv_group_avatar`) — Glide's caching,
  placeholder, and circular-crop transform pipeline would be lost if these were
  converted to canvas. No benefit.
- `fl_select_overlay` / `iv_check` / `v_check_ring` — shown only during
  selection mode (rare). Converting for marginal gain not worth added risk.
- `ChatListLastMessageView` — already canvas since v23/v24.

## Files modified

```
feature-chat/src/main/res/layout/item_chat.xml              (layout v82)
feature-chat/src/main/res/layout/item_group.xml             (layout v82)
feature-chat/src/main/java/…/chatlist/ChatListAdapter.java  (VH updated)
feature-chat/src/main/java/…/group/GroupAdapter.java        (VH updated)
```

## New files

```
feature-chat/src/main/java/…/chatlist/canvas/ChatListNameTimeView.java
feature-chat/src/main/java/…/chatlist/canvas/ChatListUnreadBadgeView.java
feature-chat/src/main/java/…/chatlist/canvas/ChatListStoryRingView.java
feature-chat/src/main/java/…/chatlist/canvas/ChatListCallButtonsView.java
feature-chat/src/main/res/drawable/bg_chat_row.xml
```

## No dependency changes

All new views use only `android.graphics.*` and `android.view.View` — no new
library dependency or Gradle change required.
