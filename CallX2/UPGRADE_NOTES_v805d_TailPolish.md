# v805d — Bubble tail: last micro-wastes removed
1. `refreshBubbleTailRow()`: no `rv.post(lambda)` while RecyclerView computes layout. Positions go into a tiny pre-allocated int queue (deduped) drained by ONE reusable Runnable; cleared on detach.
2. `refreshBubbleTailRow()`: new `mayBeBound()` O(1) check (first/last child adapter position). Rows outside the bound range skip the `findViewHolderForAdapterPosition()` child scan (prepended older pages, bursts of small DiffUtil inserts) and go straight to the payload notify. Unsure -> full lookup, same as before.
3. `setReply()`: re-resolves the shared drawable only if it is missing or was resolved for a different tail state (`bubbleDrawableTail`); the bind*() path has already resolved it.
4. `BubbleTailDrawable.buildTail()`: sent mirror done with a sign factor — no `Matrix` allocation. Tail shape is identical.

## v805e — Fused tail patch + cheaper light-mode shadow
- `BubbleTailDrawable`: the squared corner and the tail are merged once (`Path.op UNION`) into one `patchPath`. A tail bubble records `drawRoundRect` + one `drawPath` (was `drawRoundRect` + `drawRect` + `drawPath`); every tail shares one HWUI path texture. Falls back to the old two-op draw if `Path.op` fails or the radius is clamped by a tiny bubble.
- Light-mode shadow: no outer `save/translate/restore` any more — the shadow shape is drawn at offset coordinates directly.
- Display lists of tail bubbles are smaller (fewer ops per cached bubble layer), and draw-time work per record is lower. Visuals unchanged.

## v805f — Build fix
- `MessageBubbleCanvasView.isBubbleTailShown()` and `setBubbleTailForBind()` are now public: MessagePagingAdapter is in another package (compile error in v805c+).

## v805g — Tail size
- TAIL_W_DP 7 -> 8, TAIL_H_DP 10 -> 12 (closer to WhatsApp/Telegram proportions; 12dp stays below the 18dp corner radius). Reserved side room 8dp now equals the media rows' 8dp margin.

## v805h — Sharper corners, bigger tail
- CORNER_RADIUS_DP 18 -> 6 and MEDIA_CORNER_RADIUS_DP 18 -> 6 (media keeps matching the bubble).
- Tail 8x12 -> 10x15dp. Reserved side room is now 10dp, so media rows (8dp margin) sit 2dp further in, same as text bubbles.

## v805i — Corner 8dp, tail 12x18dp
- CORNER_RADIUS_DP and MEDIA_CORNER_RADIUS_DP 6 -> 8. Tail 10x15 -> 12x18dp (media rows sit 4dp further in than before: 12dp tail room vs 8dp margin).

## v806 — Chat avatars on BOTH sides (1:1 + group), tail-style
- Avatar circle now shows beside text/other bubbles for received (left) AND sent (right), in 1:1 and group chats, on the run tail (bottom-aligned), 28dp (fits the shared TINY tier -> no extra decode/cache entry). Gap 4dp + tail zone.
- No extra calls: group = live groupMemberPhotos (sent falls back to auth photo); 1:1 = partner photo (same URL as toolbar header) + auth photo, set once via `setPartnerPhotoUrl()` / constructor default. Bind = L2 `peekInline` (no allocation) else the shared `ChatAvatarBinder` pipeline.
- Layout: column reserved on all bubbles of a run (aligned bodies); sent side reserves on the right. Draw-only gate (`groupSenderAvatarShown`) so moving the avatar never re-measures.
- Neighbor change (insert/remove): observer now runs for 1:1 too, with an O(1) `mayBeBound` + "already correct" short-circuit (no payload when the bound row is right). Deferral queue shared with the tail refresh (no lambda per call).
- Tap/long-press only for received GROUP avatars (`setGroupSenderAvatarTappable`); sent/1:1 avatars are display-only.
- Placeholder bitmap pre-set on every pooled canvas holder (sent + received) in all chats.
- Files: MessageBubbleCanvasView, MessagePagingAdapter, ChatActivity (partner photo wiring).

## v807 — Avatar moves to the TAIL bubble
- Chat avatar now shows on the SAME bubble as the bubble tail (first bubble of a same-sender run) instead of the run's last bubble. Gate = `drawsTailCapableBubble(m) && isBubbleRunHead(pos, m)` (`isGroupAvatarRunTail()` now delegates to it). Size/gap/bottom alignment, column reservation on all bubbles of a run, bind pipeline unchanged.
- `avatarEligible()` additionally requires a tail-capable bubble: status_seen / reel_seen (own avatar), reel/contact/location cards, call entries and view-once rows get NO chat avatar and no reserved column — their code is untouched.
- Avatar now depends on the PREVIOUS row only (same as the tail). AdapterDataObserver refreshes the row BELOW an insert/remove for 1:1 as well (removed: avatar's old "next row" dependency).
- File: MessagePagingAdapter only.

## v808 — Avatar–tail gap 0dp
- `GROUP_AVATAR_GAP_DP` 4 -> 0: the avatar now sits directly against the tail zone (tailInset still added on top, so tail/bubble geometry is unchanged). Bubble max width gains 4dp back (reserved avatar column 32dp -> 28dp).
- File: MessageBubbleCanvasView only.

## v809 — Avatar top-aligned to the tail
- Avatar rect now starts at `bubbleRect.top` (was bottom-aligned), so it stays level with the tail tip on tall/multi-line/media bubbles. Single-line bubbles look the same as before.
- onMeasure: row height is floored to the avatar's bottom so a bubble shorter than 28dp never clips the avatar.
- File: MessageBubbleCanvasView only.

## v810 — "Show avatars" setting
- New `ChatAvatarPrefs` (feature-chat, `conversation` pkg): SharedPreferences `chat_display_prefs` / `show_chat_avatars`, default ON, read via a static boolean (loaded once in adapter `onAttachedToRecyclerView`, no prefs hit per bind).
- `MessagePagingAdapter.avatarEligible()` returns false when OFF -> no avatar AND no reserved column (bubbles get full width, 28dp back). `onAvatarSettingChanged()` does a full rebind of bound rows (column is layout-affecting).
- `ChatCustomizationBottomSheet` (chat menu -> customization): new "Show avatars" switch row; sheet height 50% -> 65% to fit it. Wired in `ChatThemeController` (1:1) and `GroupChatActivity`, both call `onAvatarSettingChanged()` on toggle.
- Setting is global (all chats, 1:1 + group, both sides).

## v811 — Time/tick on one visual row
- Tick glyph sat ~4-5dp below the footer time's digit centre (y = baseline - 0.4*size), so time and tick looked like two rows. New `drawTick(..., alignToText)` overload: `true` uses y = baseline - 0.78*size (tick centre == digit centre).
- Only `drawFooter` (text / audio / file / caption footers) passes `true`. `MediaRenderer`, `MediaGroupRenderer` and `ReelShareRenderer` pills keep the old offset (unchanged). No width/height/measure change.
- File: MessageBubbleCanvasView only.

## v812 — Time/tick alignment for media pills + tick no longer cut at the bubble bottom
- Captionless image pill (`MediaRenderer`) and captionless media-group pill (`MediaGroupRenderer`) now call `drawTick(..., alignToText=true)`: tick centre == pill time-digit centre (same row). Captioned media / media-group already go through `drawFooter` (v811).
- Cut-off fix: with the old offset the glyph's ink reached ~baseline+4.8dp while footer baseline sits only ~vPad*0.4 above the bubble bottom, so the tick's lower half was clipped by the bubble/pill edge. The aligned offset puts the ink bottom at ~baseline+1dp — inside the bubble.
- `ReelShareRenderer` (reel card pill) and seen bubbles intentionally untouched (legacy offset).

## v813 — Swipe reply fires on RELEASE (1:1 + group)
- `SwipeReplyHandler`: reply no longer fires while the finger is down. While dragging, crossing the trigger only gives haptic + icon pop (armed); coming back under it disarms. The reply is decided on the first non-active `onChildDraw` (= exact release position): `|dX| >= trigger` -> `onSwipeReply`, otherwise nothing. So swipe out -> drag back to the resting spot (or anywhere short of the trigger) -> lift = no reply.
- Removed the old fire-on-cross + cancel-on-return (35%) logic; `onSwipeReplyCancelled()` stays in the interface as a default no-op (ChatActivity's override is now unused, harmless).
- Rows with no `senderId` (system/date rows) are not swipeable.
- Group chat had NO swipe-to-reply: `GroupChatActivity` now attaches the same `SwipeReplyHandler` (-> `startReply(message)`, which keeps the admin-only-send guard). Sent rows swipe left, received rows swipe right (RTL flips), deleted rows ignored.

## v814 — WhatsApp-style reply preview bar (1:1 + group)
- `layout_chat_reply_preview.xml` rebuilt: rounded inner card (clipToOutline) with a 4dp accent strip on the left, bold sender name, a small type icon + label row (text up to 3 lines, non-italic), thumbnail flush on the right edge, and an "X" close button (was a back-arrow `ic_back`) at the top-right — over a translucent disc when a thumbnail is shown.
- New `ReplyBarLayout` (root of that layout): while visible it merges with `cv_input_capsule` — own top corners 28dp, capsule switches to `bg_chat_input_capsule_reply` (bottom-rounded) and its top margin goes 6dp -> 0, so reply card + input read as one rounded container; restored on hide. Done in `onVisibilityChanged`, so no call site changes. (Hairline capsule stroke is dropped only while a reply is showing.)
- New `ReplyPreviewBinder` (shared by `ChatActivity.activateReplyDirect` and `GroupChatActivity.startReply`): labels "Photo", "Video (0:07)", "Audio (0:07)", "GIF", file name, "Contact: X", "Poll: Q"…; gallery-item replies keep their caption/Photo/Video + thumb. Thumb loads at 192px (was 720).
- Group chat reply bar now shows type icon/label, "You" for own messages and the thumbnail (it showed only name + `[type]` before).
- Ids kept (`ll_reply_bar`, `view_reply_accent`, `tv_reply_bar_name`, `tv_reply_bar_text`, `iv_reply_bar_thumb`, `btn_cancel_reply`); new `iv_reply_bar_type`. New colors `chat_reply_card_bg`, `chat_reply_close_bg` (day/night).
