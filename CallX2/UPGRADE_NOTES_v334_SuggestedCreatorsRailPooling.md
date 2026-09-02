# v334 — Suggested Creators header rail: view pooling + correction

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## Correction first
The previous review said the "Suggested Creators row" rebuilds fresh Views
+ a fresh click listener every time it "binds/re-scrolls." On closer look
there are actually **two different** "Suggested Creators" surfaces in this
codebase, and that description only fit one of them:

1. **Inline "Suggested for you" row**, mixed directly into the scrolling
   feed every `SUGGESTED_EVERY_N_POSTS` posts
   (`ROW_SUGGESTED_CREATORS` → `bindSuggestedCreatorsRowContent()` →
   `SuggestedCreatorsTileAdapter`). This one was **already** upgraded to a
   real nested `RecyclerView` with a shared `RecycledViewPool`
   (`SUGGESTED_CREATORS_TILE_POOL`) — chips are only built/bound for
   candidates actually on/near screen, and a chip scrolled out of one strip
   hands straight to the next strip with no re-inflation. Nothing wrong
   here; not touched by this pass.

2. **The header's static "Suggested Creators" rail**
   (`containerSuggestedCreators` → `renderSuggestedCreators()` →
   `addCreatorCards()`) — a separate, older Explore-adjacent strip pinned
   at the top of Home (the "old standalone... rows" the v243 notes
   mention still exist as supplementary rails). This one lives at header
   position 0, so it never rebinds on scroll — but it WAS doing a full
   `removeAllViews()` + rebuild-every-card-from-scratch (fresh
   avatar/name/subtitle/Follow-button View tree + a fresh Follow-button
   click listener + a fresh card-tap listener, per card) on every single
   pull-to-refresh / feed reload, via `clearAllSections()` →
   `clearContainerKeepLoader()` wiping it first. This is what actually
   needed the fix, just less often (per refresh, not per scroll frame)
   than originally described.

## What changed
Applied the same "cache the View, refresh the data" rule already used for
the action-bar pill pool (v323) and the inline chip `RecyclerView` (above)
to this rail:

- New `suggestedCreatorCardPool` (`List<LinearLayout>`) — one physical card
  View tree per pool slot, built once via `obtainSuggestedCreatorCard(i)`
  and reused for every future refresh's i-th suggestion.
- New `SuggestedCreatorCardTag` stored via `card.setTag(...)` — holds the
  CURRENT uid/name/photo/isFollowed for that slot. Both the Follow button's
  click listener and the card's tap-to-profile listener are registered
  **once**, at card-creation time, and read this tag fresh at click time —
  same allocation-free pattern the pill pool uses — instead of capturing
  uid/name/photo/isFollowed in a new closure on every rebuild.
- `clearAllSections()` no longer calls `clearContainerKeepLoader()` on
  `containerSuggestedCreators` — the pool now hides (`GONE`), never
  destroys, so `addCreatorCards()` can update pooled cards in place. Side
  benefit: last session's suggestions stay visible during a refresh
  instead of the rail going blank until the new fetch resolves.
- Pool slots beyond the current candidate count are hidden, not removed,
  so a shorter list this time doesn't lose the pooled Views for next time.
- The "No suggestions yet" empty-state TextView is looked up by tag and
  toggled visible/gone rather than re-added every time, so repeated
  empty results don't stack duplicate TextViews into the container.

## Not touched
`SuggestedCreatorsTileAdapter` / the inline scrolling row (already fine,
see correction above), `SuggestedReelsTileAdapter`, the sponsored slot and
pagination fix from v333, ranking, ExoPlayer pool, watch tracking — all
unchanged.
