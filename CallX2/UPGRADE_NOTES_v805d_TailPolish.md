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
