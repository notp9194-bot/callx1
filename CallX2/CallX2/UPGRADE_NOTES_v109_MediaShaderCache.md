# v109 — MediaRenderer BitmapShader Cache

## What I found continuing from v108

While tracing why the indeterminate download spinner forces a full-bubble
redraw, checked whether `MediaRenderer` (single image/video bubble) caches
its `BitmapShader` the way `MediaGroupRenderer` already documents doing.
It didn't: `draw()` built a **brand-new `BitmapShader`** — including the
scale/translate matrix setup — on every single call, and a **brand-new
`Paint` object** for the GIF badge background on every call too. During an
indeterminate spinner (up to ~30fps after v108's throttle, was 60fps
before), that's the shader being rebuilt 30+ times a second for a bitmap
and rect that never changed.

This is the actual "cache the expensive per-draw object" fix in the spirit
of what was asked for — just scoped to what's genuinely safe to verify by
inspection, the same pattern `MediaGroupRenderer` already uses successfully
in this codebase.

## What changed — `MediaRenderer.java`

- Added `cachedShaderBitmap` / `cachedShader` / `cachedRectLeft/Top/Right/Bottom`
  fields. `draw()` now reuses the cached `BitmapShader` whenever both the
  bitmap **reference** and `mediaRect`'s exact bounds match what the cache
  was built from; any bitmap swap or rect change falls through to an
  ordinary fresh build, identical to the pre-v109 code path.
- Added a reused `gifBadgeBgPaint` field instead of allocating a `new Paint()`
  every call (its color never actually varies — always `0xCC000000`).
- When `mediaBitmap` is null (not decoded yet), the cache is explicitly
  cleared, so a bitmap arriving later is guaranteed to take the fresh-build
  branch rather than depend on object-identity luck.

## Why this is lower-risk than full Picture caching

The invalidation condition is a direct equality check against the exact
inputs the shader is built from (bitmap reference + rect bounds) — not a
manually-tracked dirty flag that has to be set correctly from every
possible mutation call site. If the bitmap or rect didn't change, the
shader output is provably identical; if either changed, the check fails
and it rebuilds. There's no scenario where this can show stale content.

## Checked, not changed

`FileBubbleRenderer` already caches all of its `Paint`/`Path` objects as
instance fields per its own class javadoc — no equivalent gap there.

Full Picture-based static-content caching for `MediaGroupRenderer`/
`MediaRenderer` (freezing everything except a live-drawn spinner) is still
the one piece I haven't done — that needs splitting each renderer's draw()
into two phases and remains real, invasive work I'd want a device to verify
against rather than ship blind.
