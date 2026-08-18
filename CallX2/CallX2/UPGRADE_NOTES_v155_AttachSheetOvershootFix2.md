# v155 — Attach sheet drag-overshoot fix, part 2

## Bug
v154's fix (`behavior.setMaxHeight(...)`) still let the sheet climb above
y=0 on drag for some users — screenshot showed the "Recents" grid pushed
up past/under the status bar.

## Real root cause
v154 computed the maxHeight ceiling from raw `DisplayMetrics.heightPixels`
minus the legacy `status_bar_height` dimen. That's the *physical screen*
size, not necessarily the actual height CoordinatorLayout gets inside the
dialog window (differs with non-edge-to-edge dialog themes, multi-window,
or IME resize). Whenever that ceiling ends up **larger** than the real
parent height, BottomSheetBehavior computes
`expandedOffset = parentHeight - maxHeight`, which goes **negative** —
sheet's top pushed above y=0. That's the exact bug.

## Fix
`computeMaxSheetHeightPx()` now derives the ceiling from the decor view's
actual current height plus `WindowInsetsCompat`'s real status-bar inset,
instead of static DisplayMetrics — and it's applied twice: once right
after bind() (best-effort, window may not be laid out yet) and again
inside the existing `topContent` global-layout listener once the dialog
window has actually settled, so a too-early first measurement can't leave
a stale/oversized ceiling behind.

Files touched:
- `feature-chat/.../controllers/AttachSheetRecentMediaBinder.java`
