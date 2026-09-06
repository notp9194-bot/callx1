# v404 — Post Details: fixed dead "Series" picker button

## Bug
`ReelPostDetailsActivity.openSeriesPicker()` tried to reflectively launch
`com.callx.app.upload.ReelDuetSeriesPickerActivity` — a class that doesn't
exist anywhere in the codebase. `ClassNotFoundException` was silently
swallowed, so tapping the Series row did nothing (no crash, no open, no
feedback).

## Fix
`ReelUploadActivity` already has a working series picker using
`DuetSeriesPickerBottomSheet` (loads the user's series from
`userDuetSeries/{uid}` in Firebase, supports "Create new series" and
"Clear series"). Reused that exact same pattern in
`ReelPostDetailsActivity.openSeriesPicker()` instead of the dead
Activity-based reflection call:

- Shows `DuetSeriesPickerBottomSheet` via `getSupportFragmentManager()`
- `onSeriesPicked(id, title, nextEp)` sets `selectedSeriesId`,
  `selectedSeriesTitle`, `selectedEpisodeNumber` and updates
  `tvSeriesPicker` text to `"<title>  (Part <nextEp>)"`
- `onSeriesCleared()` resets all three fields and sets the label to "None"

Also removed the dead `onActivityResult` branch for the old
`requestCode == 9901` (no longer needed — the bottom sheet delivers its
result via a direct listener callback, not `startActivityForResult`).
`RESULT_SERIES_ID` / `RESULT_SERIES_TITLE` / `RESULT_EPISODE_NUMBER`
already flowed correctly into the result Intent, so no other change to
the post/save path was needed.

## Verified
No remaining references to `ReelDuetSeriesPickerActivity` or request code
`9901` anywhere in `ReelPostDetailsActivity.java`.
