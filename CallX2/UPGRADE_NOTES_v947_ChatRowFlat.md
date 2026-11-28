# v947 — Chats row flattened: 10 views -> 5, no per-row Glide target, no per-bind background swap

Row tree before (v946): FrameLayout(bg_chat_row) > LinearLayout > [ FrameLayout(58dp) > {StoryRingView, CircleImageView, ViewStub},
ChatRowContentView, LinearLayout(ll_meta_normal) > {UnreadBadgeView, CallButtonsView} ]   (3 nesting levels, 10 views)
Row tree now:           FrameLayout > LinearLayout > [ ChatRowAvatarView, ChatRowContentView, ChatRowMetaView ]   (2 levels, 5 views)

## 1. Row background (one place)
- item_chat.xml no longer has android:background. The old `bg_chat_row` drawable was replaced by a ColorDrawable on the first
  bind of every row anyway (wasted drawable + invalidate + re-record).
- ChatListAdapter.applyRowBg(): ONE ColorDrawable per ViewHolder, attached only while a tint (selected / special request) is
  active and recoloured in place. Unchanged tint (every scroll bind) = zero work. Visual result is identical to v946
  (transparent row, same tint colours).

## 2. ChatRowMetaView (badge + call buttons merged)
- Replaces ll_meta_normal LinearLayout + ChatListUnreadBadgeView + ChatListCallButtonsView. One onMeasure, one onDraw
  (roundRect + text + 2 vector icons). Geometry matches the old LinearLayout exactly (incl. the 6dp call-row top margin),
  so ChatListTextPrecompute is unchanged.
- Touch: only the 34dp call strip is interactive (video = left half, voice = right half); everything else returns false on
  DOWN so the row click / long-click still fires.
- requestLayout only when the measured size really changes. Call icon drawables share one ConstantState per process.

## 3. ChatRowAvatarView (ring + avatar + border + selection overlay merged)
- Replaces the 58dp FrameLayout + ChatListStoryRingView + CircleImageView + the 3-view selection overlay (ViewStub removed,
  item_chat_select_overlay.xml deleted). Draws: story ring -> avatar bitmap -> 1.5dp brand_primary border -> selection scrim /
  check circle, in one display list.
- Avatar bitmap comes straight from ChatAvatarBinder's bitmap pipeline (new rowUrl / peekRow / bindRow) — same URL, same L1/L2
  keys as before, but no Glide Target / ImageView / BitmapShader per row.
- Bitmap is KEPT across recycle (only the in-flight load is cancelled), so rebinding the same user to the same ViewHolder
  (tab reopen, diff change) is a pure no-op: no decode, no invalidate, no re-record. (Before: Glide.clear() on recycle dropped
  the bitmap and reopen re-set it.)
- Per-VH memo of the row URL (photo/version/group) -> no URL string building on unchanged binds.
- Placeholders (ic_person / ic_group, incl. the small bordered group icon) are drawn as vectors, same as before.
- One click / long-click pair on the view (ring-click and avatar-click resolved to the same actions).
- StoryRingRegistry now hosts the avatar view.

## Files
NEW  canvas/ChatRowAvatarView.java, canvas/ChatRowMetaView.java
EDIT item_chat.xml, ChatListAdapter.java, ChatsFragment.java (recycler listener), ChatAvatarBinder.java (+rowUrl/peekRow/bindRow),
     CallxApp.java (class preload list), baseline-prof.txt
DEL  res/layout/item_chat_select_overlay.xml
Old ChatListUnreadBadgeView / ChatListCallButtonsView / ChatListStoryRingView stay (GroupAdapter / item_group still use the badge).

Not build-tested here (no Gradle/Android SDK in this environment; only a javac syntax pass). Verify on device:
- Layout Inspector: views per Chats row = 5.   - Perfetto: Chats tab reopen -> no Glide requests, no per-row bg invalidate.
- Visual diff vs v946: avatar border, story ring (seen/unseen), group placeholder, unread pill (+muted), call icons, selection mode
  scrim + green check, row tint, avatar tap / long-press zoom, call-button taps, row tap outside the call strip.
