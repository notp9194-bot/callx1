# v804 — Neon quick-forward button (chat media bubble side button)
- New `ForwardNeonButton` (feature-chat/.../conversation/canvas): dark disc + glowing violet ring + white curved share arrow, same look as the reference screenshot.
- Tap = 1.6s animation (9-frame timeline: solid ring → shock-wave + spark → ring turns to dashes → dotted ring → sparkle burst → arrow dash with motion trail/speed lines → arrow hits ring edge → solid ring, arrow back).
- Real forward action fires at `ForwardNeonButton.ACTION_AT_MS` (1400ms, arrow launch frame). Change that constant to fire earlier/later. Extra taps while animating are ignored.
- `MessageBubbleCanvasView`: button moved from the cached static bubble into `drawDynamicOverlayLayer()` so the animation redraws without re-recording the bubble; idle look is a cached bitmap blit (no per-frame cost). Animation + pending action cancelled on detach / recycle (no wrong-message forward).
- Button size 30dp -> 38dp, margin 0.
- Legacy View path (`item_message_sent/received.xml`): `ic_forward_neon` vector + scale/spin animation, then `onForward`.
