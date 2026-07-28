# v54 — Fix: Liked / Saved / Repost tabs showing wrong reels

## Bug
`UserReelsActivity` uses ONE shared `ReelGridAdapter` instance for all four
grid tabs (Reels, Liked, Saved, Repost). The adapter was constructed
**once** in `onCreate()`:

```java
adapter = new ReelGridAdapter(this, activeTabData(), ...);
```

At that point `activeTab == TAB_REELS`, so `activeTabData()` returned
`reelsTabData` — and the adapter's internal `reels` field was `final`,
permanently locked to that list.

When the user switched to Liked/Saved/Repost, `onTabSelected()` only called
`adapter.notifyDataSetChanged()` — it never told the adapter to look at a
**different** list. So every tab kept re-rendering `reelsTabData` (the
Reels tab's own posts), regardless of which tab was actually selected —
Liked/Saved/Repost all silently showed the same (wrong) content.

The Series tab was unaffected — it uses its own separate
`seriesAdapter` / `rvSeries`, correctly fed from `seriesTabData`.

## Fix
**`ReelGridAdapter.java`**
- `reels` field is no longer `final`.
- New method `setDataList(List<ReelModel> newReels)` re-points both
  `reels` and `displayList` at a different backing list and refreshes.

**`UserReelsActivity.java`**
- `onTabSelected(...)`: before notifying, now calls
  `adapter.setDataList(activeTabData())` for any non-Series tab, so the
  adapter always renders `reelsTabData` / `likedTabData` / `savedTabData`
  / `repostsTabData` — whichever matches the tab actually selected.

Because these four lists (`reelsTabData`, `likedTabData`, `savedTabData`,
`repostsTabData`) are stable `final ArrayList` instances that are only
ever `.clear()`'d + `.addAll()`'d (never reassigned), re-pointing the
adapter at the correct one on tab switch is sufficient — future
pagination/refresh appends to the same object the adapter is already
watching, so no extra wiring was needed anywhere else (Firebase loaders,
pagination, pull-to-refresh, Room warm-start for the Reels tab, and the
Reels-tab filter chips were all already correct and untouched).

## Files changed
- `feature-reels/src/main/java/com/callx/app/profile/ReelGridAdapter.java`
- `feature-reels/src/main/java/com/callx/app/profile/UserReelsActivity.java`
