# v829 — Bubble bind hot-path trim
1. `computeSizeSignature()`: message text enters the signature as length+hashCode (`appendTextKey`), not a full copy of the body, on every bind.
2. `formatBody()`: markdown result cached in `BODY_MD_CACHE` even when EmojiCompat isn't ready yet (cold chat open); emoji pass still layered on top later into `BODY_SPAN_CACHE`.
3. `onBindViewHolder`: `glide(ctx)` + target cancel/clear only when the holder actually owns a Glide target (`VH.hasAnyBitmapTarget()`); plain-text rows skip it.
4. `bindCanvasMessage`: `textHasUrl(m)` memoized on Message (`cachedHasUrl/cachedUrlSrc`, transient); 11 sequential `equals(type)` replaced by one String switch.
5. `resolveReplyColors`: received-side colors cached per night-mode state; `setColor` x5 skipped when (sent, night) unchanged.
6. `indexMessagePosition`: `put()` (Integer boxing) only when the stored position differs; `id` skipped when equal to `messageId`.
7. `MessageBubbleCanvasView.bind()`: identical plain-text rebind on an already-recorded bubble returns early (no flag reset, no `invalidate()` -> RenderNode survives). Guard: `lastBindWasPlainText` (cleared by every other bind*()), same text/time/sent/read/delivered/text color, and `!fullBubbleDirty && !staticPictureDirty`; size signature is still re-checked.
Not changed: PagingConfig / item view cache (already tuned in ChatActivity), ChatThemeManager.getTextColor (already cached).
Not compiled offline — run CI.
