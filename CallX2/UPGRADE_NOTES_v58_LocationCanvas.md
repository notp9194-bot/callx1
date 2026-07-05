# v58 — Location Canvas Bubbles

Extends the Phase-1 Canvas rendering path (`MessageBubbleCanvasView`) to cover
`"location"` (location-share) bubbles — the last item from the
"gif/file/poll/location — all still on the old path" list (contact was
covered in the previous pass; this pass does location).

## What moved to Canvas

- `isCanvasEligible()` in `MessagePagingAdapter` now returns `true` for
  `"location"` messages (previously always fell through to the old
  View/ViewStub path via `stub_location`).
- Old View path (`item_msg_location.xml` / `ChatLocationShareController.
  bindBubble()`) is **untouched** and still exists as a fallback for any
  code path that still constructs `TYPE_SENT`/`TYPE_RECEIVED` view-holders
  directly.

## New in `MessageBubbleCanvasView`

- `bindLocation(mapThumb, address, isSent)` — binds a bubbleless 165dp-wide
  card, same shape family as `bindContact()`: a purple (`#4A148C`)
  map-thumbnail header, a translucent purple (`#6A1B9A`) address strip
  (up to 2 lines), and a bottom "Open in Maps" action row. No
  timestamp/tick footer at all, matching `item_msg_location.xml` having
  none.
- `setLocationMapBitmap(bitmap)` — swaps in a decoded Google Static Maps
  thumbnail once Glide resolves it, no re-measure needed (same fixed card
  size), mirroring `setContactAvatarBitmap()`.
- When no map bitmap is supplied (no Maps API key configured, or lat/lng
  is 0), the header falls back to a placeholder pin drawn straight on the
  Canvas (circle + teardrop `Path`) instead of loading `ic_location_pin`
  as a drawable — same "flat purple bg + pin glyph" look as the legacy
  `ivMapThumb`/pin-overlay combo, just without an extra child View.
- New `OnBubbleClickListener.onLocationOpenMapsClick()` — fired only when
  the "Open in Maps" row itself is tapped (the only clickable sub-region,
  same precedent as `onContactViewClick()`'s "View Contact" row); the rest
  of the card has no tap target in the legacy layout either, just
  long-press for the action sheet.

## Adapter wiring (`MessagePagingAdapter`)

- `bindCanvasMessage()` has a new `isLocation` branch: builds the same
  address-or-`"lat, lng"` fallback text `ChatLocationShareController.
  bindBubble()` does, calls `cv.bindLocation(null, addr, sent)`, then
  Glide-loads the same Google Static Maps thumbnail URL (reusing the
  Maps API key via a new `ChatLocationShareController.getMapsApiKey()`
  getter) into `cv.setLocationMapBitmap()` when a key is configured.
- `onLocationOpenMapsClick()` mirrors `bindBubble()`'s `btnOpenMaps` click
  listener exactly: `geo:` intent to the Google Maps app first, falling
  back to a `maps.google.com` URL if it isn't installed.

## Known simplifications (documented in code, same spirit as v51/v52/v55/v56)

- No live map tile rendering — just a static thumbnail Bitmap (same as
  the legacy path) or a placeholder pin.
- GIF/file/poll remain on the old View path — location was the last item
  on the "still to do" list.
