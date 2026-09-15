# v378 — onMeasure() StaticLayout cache: all four phases complete

Supersedes v377 (Phase 1+2 only). This pass adds Phase 3 (wire onMeasure()
to actually read/fill the caches) and Phase 4 (verification), finishing the
work started in v377 for all four sites: pollQuestionLayout,
reelCaptionLayout, locationAddressLayout, linkTitleLayout.

## Correctness fix found before wiring Phase 3

Discovered while wiring: these four layouts are drawn directly via
`layout.draw(canvas)` inside PollRenderer / ReelShareRenderer /
LocationRenderer / LinkPreviewRenderer — unlike sTextLayoutCache's caption
path, which mutates `textLayout.getPaint().setColor(...)` after a cache hit.
That means color (and, for reelCaptionLayout, the drop shadow) had to be
baked into the cached layout's paint at build time, or a cache hit would
silently render with the wrong appearance. Fixed in v377's precompute
methods before this pass touched onMeasure() at all — see v377 notes /
this file's "Shared builders" section below for the fix.

## Second correctness fix, found while wiring Phase 3

The original v377 plan had onMeasure()'s cache-miss fallback build with the
*instance's own* `pollQuestionPaint`/`reelCaptionPaint`/
`locationAddressTextPaint`/`linkTitlePaint` field, same as the pre-existing
code did. That's wrong once the result is going into a **static** cache
shared across every view instance: a later per-instance mutation of that
paint field (recycled-view rebind, a theme change, anything) would
retroactively change the rendering of every other cached hit for that same
text+width, on views that have nothing to do with the view that mutated it.

Fixed by extracting four shared builders —
`buildPollQuestionLayout()` / `buildReelCaptionLayout()` /
`buildLocationAddressLayout()` / `buildLinkTitleLayout()` — each building
with a **fresh, dedicated TextPaint** (never an instance field), same
principle as the existing `buildReplyLayoutPair()`. Both
`precomputeXxxLayoutIfPossible()` (background hook) and onMeasure()'s
cache-miss path now call the same builder, so there's exactly one place
each layout's paint styling is defined.

## Phase 3 — onMeasure() wiring

All four sites now do: compute the cache key (`text.length() + "_" +
text.hashCode() + "_" + width`, same convention as every other cache in
this file) → synchronized cache lookup → hit: reuse directly; miss: call
the shared builder, and if it returns non-null, insert into the cache (a
`null` return — should only happen on a genuine `StaticLayout` exception —
falls back to the exact pre-existing synchronous build for
`pollQuestionLayout`/`locationAddressLayout`/`linkTitleLayout`; for
`reelCaptionLayout` it degrades to no caption shown, matching this file's
existing "never let a build failure affect anything" convention elsewhere).

Net effect: a poll/reel/location/link message that was already precomputed
off the UI thread (see v377's hooks — `entityToModel()` for the first
three, `LinkPreviewFetcher`'s background executor for link title) now skips
the `StaticLayout.Builder...build()` line-breaking cost in `onMeasure()`
entirely on scroll-rebind. A cache miss (first-ever bind before precompute
had a chance to run, or precompute was disabled because no bubble of that
type had been measured yet this session) still builds synchronously exactly
as before, then populates the cache for the next bind.

## Phase 4 — verification

- Grepped for leftover raw `StaticLayout.Builder...build()` calls outside
  the shared builders / documented fallback branches — none found; all four
  sites route through cache-lookup-or-shared-builder.
- Confirmed each `buildXxxLayout()` helper has exactly 3 references: its
  definition, the precompute hook call, and the onMeasure() call.
- Confirmed the four instance paint fields (`pollQuestionPaint` etc.) are
  still referenced (still get their fixed color/size/bold/shadow set in the
  resolve-paints method, and still used in the synchronous-fallback
  branches for three of the four sites) — nothing dangling/unused.
- Brace/paren sanity check on `MessageBubbleCanvasView.java`: braces
  525/525 balanced; paren count off by 2, matching the exact same
  pre-existing baseline imbalance already confirmed unrelated to any of
  this work (present before v235/v236 as well).
- Same sanity check on the other three edited files (`ChatActivity.java`,
  `GroupChatActivity.java`, `LinkPreviewFetcher.java`) — braces balanced on
  all three; `LinkPreviewFetcher.java` parens balanced exactly; the small
  pre-existing paren imbalances in `ChatActivity.java`/`GroupChatActivity.java`
  were confirmed to sit outside every block touched by this change (visually
  inspected each added `if` block — all balanced on their own).

## Net result

All four onMeasure() StaticLayout sites flagged after the FontMetrics
zero-alloc pass are now cached, background-precomputed, and
paint-mutation-safe. Combined with the existing text/poll-option/reply
caches, `MessageBubbleCanvasView`'s onMeasure() no longer does synchronous
line-breaking work for any message type on a re-bind that was already seen
this session (modulo the self-calibration warm-up: caches stay disabled for
a given layout type until at least one real bubble of that type has been
measured once, same convention as every other cache in this file).
