# v852 — Media footer strip (time + tick) drawn live

- `MediaRenderer.drawFooterStrip()` is called from `drawDynamicOverlayLayer()`; the strip is no longer part
  of the cached bubble RenderNode.
- `setDeliveryStatus()` / `setExpiryText()` on a captionless media bubble now invalidate only the 22dp strip
  rect (no `fullBubbleDirty`) -> a tick flip never re-records the bubble / photo.
- Payload path unchanged: FLAG_STATUS -> setDeliveryStatus (no rebind, no Glide, no measure).
- Captioned media, voice strip, albums, text bubbles: unchanged.
Built without Gradle/SDK — run a normal build.
