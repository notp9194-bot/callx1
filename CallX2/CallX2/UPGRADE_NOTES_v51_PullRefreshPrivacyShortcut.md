# v51 — Status Tab: Pull-to-Refresh + Status Privacy Shortcut

## 1. Pull-to-refresh added (`fragment_status.xml` + `StatusFragment.java`)
- Wrapped `rv_status` in `androidx.swiperefreshlayout.widget.SwipeRefreshLayout`
  (`swipe_status_refresh`).
- Added `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` dependency to
  `feature-status/build.gradle` (not previously declared there).
- `setOnRefreshListener` re-runs `loadStatuses()` (the blocks → contacts →
  status-listener chain). Note: the actual status/seen data already comes
  from realtime `addValueEventListener`s, so it's always live — the swipe
  gesture mainly re-syncs the one-time blocks/contacts reads and gives the
  user the expected "refreshing" feedback. Spinner auto-hides after 1s.

## 2. Status privacy shortcut added (`fragment_status.xml` + `StatusFragment.java`)
- New `btn_status_privacy` icon (`ic_shield`, from `core`) placed next to the
  search bar.
- Tapping it opens the existing `StatusPrivacyBottomSheet` (previously only
  reachable from inside `NewStatusActivity`'s compose flow) — reused as-is,
  no changes to that class. Selecting a mode persists via
  `StatusPrivacyManager` same as before.

## Files touched
- `feature-status/build.gradle`
- `feature-status/src/main/res/layout/fragment_status.xml`
- `feature-status/src/main/java/com/callx/app/feed/StatusFragment.java`
