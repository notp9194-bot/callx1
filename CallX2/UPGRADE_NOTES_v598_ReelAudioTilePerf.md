# v598 — Reel audio tile performance pass

Bind path (ReelUiController, btn_create_audio) made allocation-free on the hot path; no new network calls.

- One process-wide immutable `RequestOptions` (CenterCrop + RoundedCorners + dontAnimate), rebuilt only when
  the decode size changes. Before: RequestOptions + MultiTransformation + 2 transforms allocated per bind.
- `tileShownUrl`: an identical rebind skips the URL build and the Glide request entirely.
- Click listener / contentDescription set only when needed (no lambda alloc per bind).
- TouchDelegate rebuilt only when the tile's bounds change (was: Rect + delegate on every layout pass).
- `soundCoverCache` / `soundCoverMissAtMs` are now access-ordered LRU maps capped at 256 (were unbounded statics).
- `release()` resets the tile state and cancels an in-flight tile load, so a recycled controller never
  paints a stale cover and no request outlives its view.
- (v597) hidden disc animator + Glide load for the GONE ticker already removed.
