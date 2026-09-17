# v383 — Multi-image grid rebuild skip (MediaGroupLayoutHelper)

## Investigated: long-press context menu inflate
Already fine — `showActionBottomSheet()` reuses a single cached
`ReactionQuickBarCanvasView` / `ReactionGridCanvasView` across every
long-press (see the "PERF ADV" comment at the top of MessagePagingAdapter's
field section); they're built once and re-attached, not re-inflated per
long-press. The `BottomSheetDialog` itself is necessarily a fresh object per
show (that's how Android dialogs work), but that only happens once per user
long-press — not a scroll/bind hot path — so there's nothing worth changing
there.

## Real find: MediaGroupLayoutHelper.populate() rebuilt from scratch every bind
This turned out to be more than the "GradientDrawable per bind" framing —
`populate()` (multi-image/WhatsApp-style grid messages) called
`container.removeAllViews()` and reconstructed the ENTIRE subtree — every
`ImageView`/`FrameLayout`/`TextView`/`GradientDrawable` and every click
listener closure — on every single `onBindViewHolder`, including a plain
scroll-recycle rebind of the exact same still-unchanged message. This was
already flagged as a known cost in the file's own comments (the download-
dedup `sDownloadingUrls` set was a band-aid for one symptom of it), but
nothing skipped the rebuild itself.

## Fix
A multi_media message's `items` and `caption` never change after send
(edits, reactions, ticks, deletion are all handled elsewhere / by other
view types — never by rebuilding this grid). `populate()` now computes a
cheap fingerprint (message id + item count + each item's url/thumbUrl/
mediaType + caption) and stores it on the container via `setTag()`. If the
next call's fingerprint matches what's already tagged there, `populate()`
returns immediately — no `removeAllViews()`, no new child views, no new
`GradientDrawable`s, no re-issued Glide loads, no new click listeners.
A real content change (different message recycled into this container, or
a genuine caption edit) still has a different fingerprint and rebuilds
normally.

Side benefit: a cell's manual-download swap (network thumb → local file)
used to get silently reset back to the network thumb the next time that
row scrolled out and back in, because the whole grid rebuilt from the
original `items` data. Since unchanged rebinds are now skipped entirely,
that already-swapped-in state simply stays as-is.
