# v804 — Neon quick-forward button (chat media bubble side button)
- New `ForwardNeonButton` (feature-chat/.../conversation/canvas): dark disc + glowing violet ring + white curved share arrow, same look as the reference screenshot.
- Tap = 1.6s animation (9-frame timeline: solid ring → shock-wave + spark → ring turns to dashes → dotted ring → sparkle burst → arrow dash with motion trail/speed lines → arrow hits ring edge → solid ring, arrow back).
- Real forward action fires at `ForwardNeonButton.ACTION_AT_MS` (1400ms, arrow launch frame). Change that constant to fire earlier/later. Extra taps while animating are ignored.
- `MessageBubbleCanvasView`: button moved from the cached static bubble into `drawDynamicOverlayLayer()` so the animation redraws without re-recording the bubble; idle look is a cached bitmap blit (no per-frame cost). Animation + pending action cancelled on detach / recycle (no wrong-message forward).
- Button size 30dp -> 38dp, margin 0.
- Legacy View path (`item_message_sent/received.xml`): `ic_forward_neon` vector + scale/spin animation, then `onForward`.

## v804b — Light-mode fix
- `ForwardNeonButton` is now theme-aware (`draw(..., dark)`, `staticBitmap(size, dark)`): dark mode = unchanged neon look; light mode = light lavender disc, deeper violet ring/glow/sparks/trail, deep-violet arrow + stronger glow so every effect stays visible on light wallpapers. Animation timeline untouched.
- Idle bitmap cache is keyed by size + mode. `MessageBubbleCanvasView.drawForwardButton()` passes `ChatThemeManager.isDarkMode()`.
- Legacy View path: `drawable/ic_forward_neon.xml` is now the light variant, `drawable-night/ic_forward_neon.xml` keeps the dark one.

## v804c — Polish pass
1. **Snappier forward:** `ForwardNeonButton.ACTION_AT_MS` 1400 -> 950ms (animation still plays the full 1.6s). Tune this one constant.
2. **Touch feedback:** canvas button scales to 0.88 while pressed (cancelled on MOVE-out/CANCEL/UP); touch target = button + 5dp slop on top/bottom/outer side (never toward the bubble). Legacy view uses `setPressed`.
3. **Reduce-motion:** if system animator scale is 0 (`ForwardNeonButton.motionReduced`), animation is skipped and forward fires immediately (canvas + legacy).
4. **Partial invalidate:** animation redraws only the button rect (+40% margin for glow/sparks/trail) instead of the whole bubble; press feedback also invalidates only that rect.
5. **Accessibility:** canvas bubble exposes a custom "Forward" TalkBack action (id = `R.id.btn_quick_forward`, forwards immediately, no animation); legacy button keeps contentDescription "Forward".
6. **Legacy path parity:** new `ForwardNeonView` (extends ImageButton, reuses `ForwardNeonButton`) replaces the old spin animation in `item_message_sent/received.xml` (44dp + 4dp padding, -4dp margin so position is unchanged). `MessagePagingAdapter` uses `play(action)` / `cancel()` on rebind.
7. **Light mode sparkles:** 1.35x bigger sparks in light palette.
