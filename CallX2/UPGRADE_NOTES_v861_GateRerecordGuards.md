# v861 - Gate re-record guards (MessageBubbleCanvasView)

- clearMediaDownloadGate(): no-op when the gate is already fully clear (no gate/upload extras/animator/a11y delegate).
  Called from 9 adapter sites (media rebind, upload complete, payload paths).
- setGroupDownloadGate(cells, sizeText): no-op when the gate is inactive before and after, same size text and cell
  count, and no cell is pending/downloading/failed.

Not touched: setMediaDownloadGate non-tick transitions (compressing/wait/retry) - real state changes; guarding them
would leave stale mediaUploadCancelable.
Verify: `Bubble.record` count on media rebinds with no gate -> should drop.
