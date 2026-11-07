# v730 — Status viewer screen: bottom-left "Seen by" avatars

1. **Core avatar system:** the bar avatars were loaded by `StatusSheetAvatar.load` (plain Glide, no L2/L3 bitmap tiers, no analytics, unversioned URL).
   Now `StatusAvatarBinder.bindCore()` -> `AvatarBinderCore.bind()` with the status module's `StatusAvatarL2Cache` (L2 memory + L3 disk),
   HIGH priority, `AvatarCacheAnalytics` + dashboard stats, and the cached `avatarVersion` in the URL (changed photo = new URL). Old loads are cancelled when the bar rebuilds.
2. **Size:** ring cell stays 36dp; avatar 32dp -> **29dp** (36 - 2*(1.5 ring + 2 gap)), so there is exactly 2dp transparent gap between ring and avatar.
   29dp still maps to the TINY (32dp) tier -> same cached bitmap as reel-comment / sheet avatars.
3. **Ring 1.5dp:** already true — `StoryRingApplier` draws unseen = `StoryRingGradientDrawable.withStrokeDp(1.5f)` and seen = `circle_status_seen_thin` (1.5dp). No change needed.

Files: `StatusViewerActivity.java`, `StatusAvatarBinder.java`. Not built / not run on device. Check: bar avatars with ring (seen + unseen) and without ring; reopen the viewer (avatars should paint instantly from L2/L3).
