# Chat area v422 — bounded lookups and safe fling tuning

## #4: bounded hot-path lookups

- `MessagePagingAdapter` now indexes attached message holders by message id.
- Local-media availability refresh, upload progress, and optimistic reaction
  updates no longer scan the loaded Paging window. Off-screen rows pick up
  cached state on their next bind.
- Reaction text formatting uses a bounded 128-entry LRU keyed by message id
  and reaction-map state.
- `LinkPreviewFetcher.extractFirstUrl()` uses a bounded 128-entry text cache,
  including negative results, and `ChatActivity` shares that detector instead
  of compiling a new URL pattern for every share.

## #5: conservative device-aware fling tuning

- The shared friction tuner now uses platform memory signals. Low-RAM and
  unknown devices stay close to stock friction; capable devices receive only
  a moderate reduction.
- `FastFlingRecyclerView` uses the matching device-aware velocity boost instead
  of a fixed aggressive multiplier.
- Reflection remains best-effort and centralized; failure leaves stock
  RecyclerView behavior intact.

Build and tests were intentionally not run.