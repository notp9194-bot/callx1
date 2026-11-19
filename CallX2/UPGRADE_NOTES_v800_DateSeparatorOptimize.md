# v800 — DateSeparatorCanvasView: zero per-row work on chat open
- Paints, px constants, font metrics, scratch RectF/Matrix are process-wide statics (recomputed only on density/fontScale change; theme colours applied once per theme change).
- Per-label text width memoized (LruCache); single-line labels (all date rows) never create a StaticLayout — drawn with drawText.
- Wrapped system lines: StaticLayout memoized per (label, width) across rows.
- Content + geometry (chip rect, radius, text baseline, rule coordinates, avatar origin) computed once per label/avatar/rules/width change; onDraw = 2 lines + 1 roundRect + 1 drawText.
- setLabel / setAvatar / setShowRules are strict no-ops when unchanged (no requestLayout, no invalidate) — rebinding a visible row costs three comparisons.
- Avatar BitmapShader built once per bitmap, re-matrixed only when geometry moved; released on detach.
- Per row instance allocation: the View only.
