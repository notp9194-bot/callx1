# v853 — Live footer for EVERY bubble type (time / expiry / tick never baked into the cached node)

Goal: a tick flip (sent -> delivered -> read) or an expiry countdown tick redraws only a tiny footer rect.
The cached bubble RenderNode (text layout, photo, album grid) is never re-recorded for it.

## Mechanism (MessageBubbleCanvasView)
- `drawFooter()` no longer paints. It records an anchor (`footerLiveValid/BaseY/RightX`) + the "edited" tap rect.
  `drawFooterLive()` paints time + expiry + tick from `drawDynamicOverlayLayer()`.
  -> covers text bubbles (inline + separate row), captioned single media, captioned albums, audio, file bubbles.
- Voice strip: `voiceStripLive()` is now always true (idle too, not only while playing). Play/pause, speed,
  downloading and tick changes redraw only the 48dp strip.
- Captionless album pill + reel-share pill: moved to `MediaGroupRenderer.drawPill()` / `ReelShareRenderer.drawPill()`,
  painted from the overlay layer at the same position (layout / hit-rects unchanged).
- `invalidateExpiryRegion(layoutChanged)`: if the footer is live (`computeLiveFooterRect()`) -> `invalidate(rect)` only.
  Falls back to the old full re-record when the reserved footer width changed (layout requested) or a piece is not live.
- Inner media/file/group download cache keeps its own copy of the anchor (`save/restoreInnerFooterAnchor`),
  so an inner-cache hit still shows the footer.
- `resetAudioPlayback()` no longer dirties the whole bubble for non-audio bubbles with no audio state.

## Payload path (unchanged)
FLAG_STATUS -> `setDeliveryStatus()` -> rect invalidate. No rebind, no Glide, no measure, no re-record.

## Test
- Text bubble: sent -> delivered -> read, tick color changes, text not re-recorded (Bubble.record trace count stays flat).
- Captioned photo, captionless photo, voice photo, album (caption + captionless), reel share, audio, file.
- Disappearing message countdown on each of the above.
- Open a chat with many "read" ticks flipping at once; scroll should stay smooth.
Built without Gradle/SDK — run a normal build.
