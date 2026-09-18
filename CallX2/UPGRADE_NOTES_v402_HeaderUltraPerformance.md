# v402 Header Ultra Performance

## MainActivity header and tab chrome

- Merged the chat, group, reel-notification, and missed-call badge work onto
  the existing four realtime Firebase listeners. The toolbar notification badge
  and bottom-navigation badges now update from the same snapshots instead of
  maintaining duplicate listeners and duplicate Firebase delivery.
- Badge listeners are attached in `onStart()` and removed in `onStop()`.
  Contact-status child listeners are tracked by reference and removed with the
  same lifecycle.
- Cached header ViewBinding references are used for the toolbar badge,
  return-to-call banner, app bar, nav container, and pager margins.
- Header/nav visibility and pager margins are changed only when their state
  actually changes. The unconditional root `requestLayout()` on every tab
  event was removed.
- The ViewPager2 offscreen limit now uses the adaptive default instead of
  eagerly retaining the adjacent Reels tab on cold start.

## Avatar and call banner

- Profile and Reels avatar metadata is cached in app-private preferences with a
  six-hour freshness window. `onResume()` no longer starts Firebase avatar
  reads or repeats the avatar Glide request.
- Reels avatar Glide work is skipped when the URL is unchanged.
- The return-to-call banner no longer uses reflection. `CallForegroundService`
  now exposes a small in-process state listener, so MainActivity reacts to
  actual call state changes. Only the visual dots timer remains periodic.

The project was not built or tested in this upgrade, as requested.