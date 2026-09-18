# v423 — Chat-area media preload and link-preview update performance

## Scope

This pass targets the two remaining chat-area jank sources called out after
the adaptive scroll/preload work. Build and automated tests were intentionally
not run.

## Changes

- `ChatActivity` no longer starts the old `onResume()` last-10-message Glide
  warm-up. `ChatMediaPreloader` remains the single bounded/adaptive look-ahead
  path, avoiding duplicate requests, decodes, and cache pressure.
- The legacy ViewStub link-preview path now lays out a stable preview slot
  immediately when a URL is detected. Async OG metadata and thumbnail results
  replace content in place instead of toggling the card and thumbnail
  visibility during a scroll.
- Canvas link-preview thumbnail completion remains draw-only and explicitly
  never requests layout; the card geometry is established by the preview
  metadata update, while the later bitmap arrival only invalidates pixels.
- Live message-status updates now use `MessagePagingAdapter`'s message-id
  position index, avoiding a full loaded-snapshot scan on normal tick changes.
  The adapter still validates and repairs stale positions defensively after a
  Paging shift.
- Multi-select enter/exit now sends a selection-only payload to the visible
  range, so alpha/highlight changes do not repeat media loads, linkification,
  or Canvas binding. Theme changes use a theme payload; Canvas holders refresh
  their paints/drawable in place, while legacy holders retain a correctness
  full-bind fallback within the visible/buffered range.

## Validation

No build or test was run, as requested. The updated archive is ready for the
user's own build/test pass.