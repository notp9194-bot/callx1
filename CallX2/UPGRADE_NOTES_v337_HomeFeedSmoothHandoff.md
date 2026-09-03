# Home Feed Smooth Handoff Pass

This source-only upgrade targets the Home tab's scroll-frame budget. The
project was not built or tested as requested; build and device verification
should be done by the app owner.

## Changes

- Removed automatic ExoPlayer attach/detach from the per-pixel `onScrolled`
  path. The existing outside-viewport guard still stops an off-screen player,
  while the IDLE/vsync pass selects one final landing reel.
- Added a lightweight signed scroll-velocity estimate to
  `HomeFeedScrollStateManager`.
- Made `HomeFeedPrefetchManager` use scroll state and velocity to reduce
  look-ahead during fast flings and skip behind-the-user prefetch.
- Enabled stable adapter IDs for Home feed rows. Post IDs are deterministic
  hashes of `reelId`; other row objects retain row-local identities.
- Added a payload-aware update path for the live new-post banner so its count
  update does not route through a generic heavy bind.

No APK, generated build output, or dependency lockfile was changed.