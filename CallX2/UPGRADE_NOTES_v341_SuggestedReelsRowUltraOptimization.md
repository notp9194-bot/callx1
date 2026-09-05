# v341 — "Suggested reels" Home-feed strip: ultra bind/allocation optimization

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`
Classes: `SuggestedReelsRowHolder`, `SuggestedReelsTileAdapter` (+ its `TileHolder`)

## Goal
The row was already using real ViewHolder recycling (nested RecyclerView +
shared `RecycledViewPool`, added in v260/v263). This pass removes every
remaining per-bind allocation and redundant work inside that hot path, so
scrolling the strip (and the outer Home feed scrolling it in/out) never
repeats an allocation or a write that isn't necessary.

## Changes

1. **Click / long-click listeners now set once, not once per bind.**
   `onBindViewHolder` used to build a brand-new `setOnClickListener` and
   `setOnLongClickListener` lambda — each capturing that bind's `position`
   and `ReelModel` — on **every single tile that scrolled into view**. Both
   listeners are now registered exactly once, in `onCreateViewHolder`, and
   read mutable state off the holder at click time instead:
   - Click resolves the position via `holder.getBindingAdapterPosition()`
     (never a captured/stale index).
   - Long-press reads `holder.currentReel`, refreshed every bind.

2. **Glide `RequestManager` resolved once per adapter, not per call.**
   `Glide.with(requireContext())` was called on every `onBindViewHolder`
   *and* every `onViewRecycled`. It's now resolved once in the adapter's
   constructor (`this.glide = Glide.with(HomeFragment.this)`) and reused
   for every load/clear this adapter ever issues.

3. **Skip redundant writes on a rebind that hands back unchanged data**
   (e.g. `notifyDataSetChanged()` from `updateItems()` rebinding a
   still-attached holder). Each `TileHolder` now tracks what it last
   showed and short-circuits when nothing actually changed:
   - `boundViewsText` — skips `setText()` if the views-count label is
     identical.
   - `boundThumbUrl` — skips re-issuing the Glide request if the same URL
     is already loaded/loading into this holder.
   - `boundMarginEndPx` — skips the `LayoutParams` mutation if the
     tile's end-margin (0 for the last tile, 8dp otherwise) is unchanged.
   All three reset to their "unbound" sentinel in `onViewRecycled` so a
   holder reused for a different reel can't be mistaken for a no-op skip.

4. **`dpToPx(8)` cached once per adapter** (`marginEndPx` field) instead of
   re-resolving the display density through `getContext()` on every bind —
   density cannot change without the Activity recreating, so this was a
   pure per-bind waste.

5. **`SuggestedReelsRowHolder`'s outer-visibility `Rect` is now a reused
   field**, not a fresh `new Rect()` on every `onScrolled` tick of the
   OUTER feed (`recyclerHome`) while the strip is attached — this listener
   can fire many times per scroll gesture, so this was the hottest
   allocation in the whole feature.

## Untouched
Candidate-pool fetch/cache logic, the header/kebab chrome, the
autoplay/ExoPlayer-pool wiring, tile sizing (160×284dp), and the peek
preview are all exactly as before — this pass is bind/allocation-path
only, no behavior or visual change.

## Verification
Brace balance confirmed after edit (1150/1150). Paren "imbalance" (5,
from parens inside comments) matches the pre-existing baseline exactly,
so no code parens were left unbalanced. **Not compiled** — no Android
SDK/Gradle network access in this sandbox. Build locally
(`./gradlew :feature-reels:assembleDebug`) before shipping.
