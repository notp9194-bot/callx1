# v406 — GroupAvatarBinder + CommunityAvatarBinder route through AvatarBinderCore

## Problem
`GroupAvatarBinder.bind()` and `CommunityAvatarBinder.bindIcon()` each still
hand-rolled their own copy of the L2-check → Glide-decode →
L2/L3-write-through pipeline — the exact shape `ChatAvatarBinder` used to
duplicate before it was extracted into `AvatarBinderCore`. Two more
standalone copies meant any future fix to that shape (e.g. the HIGH-priority
bind, or the "skip re-request if this exact URL is already loaded into this
view" tag check) would need manually re-applying to 3 places instead of 1.

## Fix
Both now delegate their ImageView bind path to `AvatarBinderCore.bind()`,
same as `ChatAvatarBinder` already does:

- **GroupAvatarBinder.bind()** → `AvatarBinderCore.bind(ctx, iv, iconUrl, 0L,
  CACHE, new BindOptions(tier, AVATAR_FORMAT, circleCrop=true,
  dontAnimate=true, recordDashboardStats=true, placeholderRes))`.
  `cancel()` → `AvatarBinderCore.cancel()`.
- **CommunityAvatarBinder.bindIcon()** → same `AvatarBinderCore.bind()` call,
  `recordDashboardStats=false` (bindIcon never recorded `CacheDashboardStats`
  before, only `AvatarCacheAnalytics` — core's unconditional analytics
  recording preserves that, the dashboard-stats flag preserves the rest).
  `cancelIcon()` → `AvatarBinderCore.cancel()`.
- Both pass `avatarVersion=0L` — groups/communities don't carry a version
  counter; `AvatarUrlBuilder#appendVersion` no-ops for `<= 0`, so the URL
  produced is byte-for-byte identical to the old un-versioned call.
- `CommunityAvatarBinder.bindBitmap()`/`cancelBitmap()` are **unchanged** —
  `AvatarBinderCore.bind()` is ImageView-only, so the canvas-target path
  stays its own implementation, same as `ChatAvatarBinder.bindBitmap()`.

## Behavior notes (read before shipping)
- `AvatarBinderCore.bind()` always calls `iv.setImageResource(placeholderRes)`
  on a null/empty URL, even when `placeholderRes == 0`. `CommunityAvatarBinder
  .bindIcon()` previously did nothing in that case (left the view showing
  whatever it had before). New behavior clears the view instead — correct
  for a recycled row whose new item has no icon, but flag if any call site
  relied on the old "leave it alone" quirk (none currently do — checked all
  `bindIcon`/`bind` call sites, they always pass a real placeholder or an
  empty/non-empty url predictably per row).
- `AvatarBinderCore.bind()` also skips re-issuing a request when the exact
  same URL is already bound to that ImageView (a `tag_avatar_url` check) —
  new for both binders, pure win for a recycled row rebinding to the same
  person.
- `GroupAvatarBinder`'s `CacheDashboardStats` keys change from
  `"group_avatar:" + url` to `AvatarBinderCore`'s own `"avatar:" + url` —
  checked, nothing in the app filters the dashboard by the old
  `group_avatar:` prefix, so group icons now simply roll into the same
  dashboard bucket chat/community avatars already use.

## Files changed
- `feature-chat/.../cache/GroupAvatarBinder.java` — `bind()`/`cancel()` now delegate; own Glide/RequestListener code removed.
- `feature-chat/.../cache/CommunityAvatarBinder.java` — `bindIcon()`/`cancelIcon()` now delegate; `bindBitmap()`/`cancelBitmap()`/`warmCache()`/`url()` untouched.

## Not done (prefetch)
No call site for either binder currently wires up `AvatarSource`/velocity —
`GroupAdapter`, `CommunityGroupAdapter`, `CommunityPostSearchAdapter`, etc.
don't track scroll velocity/fromIndex today. Wiring `AvatarBinderCore.prefetch()`
for these would mean adding that plumbing to each adapter first; noted as a
follow-up rather than added as dead code.

## Remaining avatar-pipeline gaps (unchanged from v405 notes)
- `AvatarBatchPrefetcher`, `AvatarColdStartQueue`, `AvatarVersionSyncManager`,
  `AvatarHttpCache` are still wired into feature-reels only.
