# v850 — Media bubble: time + tick moved off the photo

Captionless single image/video/gif no longer draws the translucent time/tick pill over the photo.
Time + tick are drawn in a slim 22dp strip BELOW the photo (same idea as the v845 voice strip).

- `MessageBubbleCanvasView`: `hasFooterStrip()`, `MEDIA_FOOTER_STRIP_DP`, `mediaFooterRect`;
  strip height added in onMeasure; `mediaPillRect` stays empty for single media;
  tick/expiry dirty-rect includes the strip.
- `MediaRenderer`: pill (bg + border round-rects, white text) replaced by plain time/tick text in the strip.
- Not changed: voice-photo strip, captioned media (footer in caption row), albums, reel-share cards.
Built without Gradle/SDK — run a normal build.
