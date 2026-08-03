# v50 — Status Tab: Modern Search Bar + Highlights Strip Removed

## 1. Search bar UI modernized (`fragment_status.xml`)
- Replaced the old `TextInputLayout.OutlinedBox` (boxed Material outline) with
  a modern pill-shaped search field: `FrameLayout` + `@drawable/bg_search_field`
  (rounded 20dp corners, theme-aware `surface_input` color for day/night).
- Matches the search bar style already used in Reels/Chat/X search screens
  (leading search icon + borderless `EditText` + inline clear/X button).
- Added a clear (X) button (`btn_status_search_clear`) that appears only while
  typing and clears the query on tap — the old TextInputLayout had no clear
  affordance.
- `EditText` id kept as `et_status_search` so `StatusFragment.java`'s existing
  `findViewById` call and `TextWatcher` logic didn't need to change.

## 2. Highlights strip removed from Status tab (`StatusFragment.java`)
- The horizontal "Highlights" strip (Instagram-style saved-status albums) was
  showing at the top of the Status tab list — but this is redundant since
  Highlights are already shown on the profile screen (`UserReelsActivity`,
  in `feature-reels`).
- Fix: stopped calling `loadHighlights()` in `onStart()` and removed the
  `setHighlightClickListener(...)` wiring in `onCreateView()`.
- Because `statusAdapter.updateHighlights(...)` is now never called, the
  highlights list inside `StatusListAdapter` stays empty, and its
  `ITEM_HIGHLIGHTS` section (which is only added to the flat list when
  `!highlights.isEmpty()`) never renders. No changes were needed to
  `StatusListAdapter.java` itself — the highlight system/adapter code is left
  fully intact for reuse elsewhere (e.g. `StatusHighlightsActivity`,
  `CreateHighlightActivity`, `UserReelsActivity`'s own highlights row).
- `loadHighlights()` remains defined in `StatusFragment.java` but unused
  (harmless unused-private-method warning only) — kept in case this ever
  needs to be re-enabled quickly.

## Files touched
- `feature-status/src/main/res/layout/fragment_status.xml`
- `feature-status/src/main/java/com/callx/app/feed/StatusFragment.java`
