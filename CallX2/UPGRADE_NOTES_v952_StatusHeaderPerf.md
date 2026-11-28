# v952 — Status tab header: fewer allocations / inflates / fills on every open

1. **Skeleton row → ViewStub** (`item_status_carousel.xml`, `layout_status_skeleton_row.xml`, `CarouselVH.ensureSkeleton()`):
   the 4 skeleton cards are inflated only when the first data is really pending, never on a restored tab.
2. **First frame from saved built lists** (`StatusTabState.saveBuilt/builtLists`, `StatusFragment.applyBuiltSnapshot()`):
   a re-created tab hands the last adapter lists straight to `adapter.update()`; the per-contact rebuild loop runs once,
   debounced, after first paint (skipped by `lastBuildSig` when nothing changed). Filtered (search) lists are never saved.
3. **Card view pool** (`StatusCardViewPool`): `item_status_card` pre-inflated off-thread (MainActivity startup queue,
   fragment create, and restocked in `onDestroyView`). Handed out only to a RecyclerView with the identical Context;
   `release()` in `MainActivity.onDestroy`.
4. **Overdraw**: `iv_card_bg` lost its own solid background; `bg_status_card_body` now uses `status_card_placeholder`
   (same look, 3 stacked fills -> 2). Hidden-tile exit clears the override with `setBackground(null)`.
5. **Dirty-flag onStart** (`rebuildDirty`): a live fragment's onStart rebuilds only if data/prefs changed (mute, hide,
   close-friends, seen prefs via `OnSharedPreferenceChangeListener`), a time label is due, or a status expired.
6. **Search field → ViewStub** (`layout_status_search_field.xml`, `inflateSearchField()`), inflated on first tap.
   **Initials avatar**: shared 144px bitmap per (uid,name) in `StatusAvatarBinder` instead of a new Drawable per bind.

Verify with Perfetto: sections `StatusTab#create`, `StatusTab#start`, `StatusTab#rebuild`.
