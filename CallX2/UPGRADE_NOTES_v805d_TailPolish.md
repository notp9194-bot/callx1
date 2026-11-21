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
