# v381 — Fixed cramped 3-dot (⋮) menu icon spacing

## What changed
`ic_more_vert.xml` (the header's 3-dot overflow menu icon) had dots packed
too close together: radius 1.8dp, centers at y=7/12/17 → only 1.4dp
edge-to-edge gap.

Fixed to match standard Material spacing: radius 2dp, centers at
y=5/12/19 → 3dp edge-to-edge gap (gap ≈ dot diameter, the usual rule of
thumb for this icon).

## Files touched
- `core/src/main/res/drawable/ic_more_vert.xml`
- `feature-reels/src/main/res/drawable/ic_more_vert.xml` (identical copy, same bug)

## Not touched
- `feature-x/src/main/res/drawable/ic_more_vert.xml` — different icon
  drawing style (rounded-square path, not circles), already reasonably
  spaced, left as-is.
