# v266 — Reel Edit screen, Step 2 (Text Overlay): ultra-advanced performance optimization

## Goal
Step 2 of the Reel/Status editor wizard (advanced multi-overlay text tool —
drag/pinch/rotate, font/bold/italic/align/background/colour/size, all baked
into the exported video via ReelVideoExportEngine) was functionally correct
but did far more work per interaction than it needed to. This pass removes
that overhead without changing behavior.

## Changes — `ReelEditorActivity.java`

1. **Debounced stickerJson rebuild.** `mergeTextOverlaysIntoStickerJson()`
   re-walks every overlay, re-parses the existing JSON, and rebuilds the whole
   array — it was firing on every single seekbar tick and chip tap. New
   `scheduleStickerJsonMerge()` coalesces bursts into one rebuild ~100ms after
   the last change via the existing `handler`. `proceedToUpload()` still calls
   the synchronous version directly right before the value is read, so upload
   correctness is unaffected — only the wasted intermediate rebuilds are gone.

2. **One-time style panel construction.** `setupAdvancedTextOverlayPanel()`
   used to `removeAllViews()` and rebuild every chip/swatch — with a brand-new
   `GradientDrawable`/`StateListDrawable` each — on *every* overlay
   select/reselect, which is the single most frequent action on this screen.
   It now builds once per session; every reselect just flips `.setSelected()`
   on the existing chips via the new `syncTextOverlayPanelSelectionUI()` /
   `syncRowSelectionByTag()` — zero view or drawable allocation.

3. **No wasted drawable allocation for "no background" text.** `applyStyleToView()`
   allocated a `GradientDrawable` unconditionally, even for `bgStyle == "none"`
   where it was immediately discarded. Now skipped entirely for that case.

4. **StringBuilder-based JSON building.** Replaced the `ArrayList<String>` +
   `TextUtils.join` two-pass approach with direct `StringBuilder` writes, and
   replaced chained `.replace().replace().replace()` text escaping with a
   single-pass `appendJsonEscaped()`.

5. **Fast hex colour formatting.** Replaced `String.format("#%06X", ...)`
   (Locale + Formatter allocation on every overlay, every merge) with a manual
   `appendColorHex()`.

6. **Zero-allocation trash-zone hit testing during drag.** `isOverTrashZone()`
   called `View.getLocationOnScreen()` (a full view-hierarchy transform walk)
   twice on *every* `ACTION_MOVE` pixel while dragging an overlay. Neither the
   trash icon nor the overlay's parent frame move mid-gesture, so their screen
   offsets are now cached once at `ACTION_DOWN` (`isOverTrashZoneFast()`);
   every subsequent move is pure arithmetic on already-known view coordinates.

## Behavior
No functional/UI changes — same chips, same drag/pinch/rotate gestures, same
exported sticker_json shape. Purely allocation/CPU-cycle reduction on the hot
interaction paths of Step 2.
