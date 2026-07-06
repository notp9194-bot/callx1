# v107 — Background StaticLayout Precompute (text + poll options)

Implements the "big gain" item flagged as risky earlier, using a design
that structurally avoids the bug that made the previous PrecomputedTextCompat
attempt get reverted (see `asyncTextEnabled`'s javadoc in
`MessagePagingAdapter.java`).

## What changed

**Plain text bubbles** (`MessageBubbleCanvasView.precomputeTextLayoutIfPossible`)
- `ChatActivity#entityToModel()` / `GroupChatActivity#entityToModel()` — which
  already run on `ioExecutor` via `PagingDataTransforms.map(...)`, off the UI
  thread — now kick off a background build of each text message's
  `StaticLayout` before the message ever reaches the adapter.
- `onMeasure()`'s plain-text branch checks a cache first; on a hit, it skips
  `StaticLayout.Builder...build()` entirely (the actual line-breaking/text-
  shaping cost) and reuses the cached layout, fixing up only its color.
- Cache key is `(text, target width)` — no message ID involved, since two
  messages with identical text/width legitimately produce an identical
  layout.
- Target width is **self-calibrating**: `onMeasure()` writes the real,
  currently-in-use width into a static field on every real measure pass, and
  background precompute reads that same field, so there's no fragile guessing
  at padding/margin arithmetic from raw display metrics.

**Poll option labels** (`MessageBubbleCanvasView.precomputePollOptionLayoutIfPossible`)
- Same pattern, wired from both `entityToModel()`s for `type == "poll"`,
  looping `m.pollOptions`.
- Simpler/safer than the text case: `pollOptionTextPaint` uses a fixed color
  regardless of sent/received, so a cached layout needs no per-bind color
  fixup at all. The vote percentage/count is drawn separately, so caching the
  label text can never show a stale vote count.

## Why this design doesn't reintroduce the old bug

The old bug was a **swap after render**: a view showed plain text first, then
later (up to 400ms after, per an async callback) had its content silently
replaced by a separately-built layout — if that second layout disagreed on
line count because the view's width wasn't 100% final yet, the bubble's
measured height and its drawn content diverged.

This implementation never swaps anything post-render:
- The cache is populated strictly **before** a message reaches the paging
  list — never after a view is bound or shown.
- `onMeasure()` makes exactly **one** synchronous decision per bind (cache
  hit → use it; miss → build fresh, identical to the pre-v107 code path).
  There is no second pass, no delayed callback, no content swap.
- The cache key includes the exact target width, so any mismatch (e.g. the
  device was rotated between precompute and bind) is simply a cache miss —
  falls back to the exact same synchronous build that ran before this
  change. Worst case is "no speedup," never wrong content.
- Each cached text-bubble entry owns its **own dedicated TextPaint**,
  never the view's shared per-instance `textPaint` field (which doubles as
  scratch space for file-card measurement and the deleted-message italic
  style elsewhere in the class) — so a cached layout can never be corrupted
  by an unrelated Paint mutation on some other code path or some other view.
- Deleted messages are explicitly excluded from the text cache and always
  take the original synchronous path.

## What I could not verify

There is no Android SDK/emulator in this environment, so this could not be
compiled or run on a device. I checked brace/paren balance across every
edited file against the pre-edit originals (deltas match exactly what was
added, with no new imbalance) as a syntax sanity check, and traced every
call site by hand, but please do a real build + a manual scroll-test
(long chats, rotation, a chat opened cold, deleted messages, and a poll with
several options) before shipping.

## Scope note

Poll/media-group **bitmap/Picture caching** (caching a fully-rendered bubble
image, as opposed to just its text layout) was not implemented in this pass.
Text-layout caching captures the large majority of the CPU win for the
common case (plain text messages, which dominate most chats) at
meaningfully lower risk than full-bubble render caching would carry — that
one still touches per-frame draw-call composition rather than just line-
breaking, and is a bigger, separate piece of work I'd want to scope on its
own.
