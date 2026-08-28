# v288 — Perf: Post tab feed no longer re-runs Glide's full load chain on a same-reel rebind

## Where
`PostsFeedActivity.PostsAdapter.onBindViewHolder()` (Post tab / profile
photo-feed screen, `item_post_feed_photo.xml`).

## Bug (scroll hot path)
This adapter was written before the Home-tab optimization pass and, aside
from the fresh-lambda/fresh-object-per-bind issues that pass already fixed
here (cached `RequestOptions`, once-registered click listeners, once-built
`GestureDetector`, cached dot-indicator views, cached formatted strings —
see the existing `PERF` comments throughout `onBindViewHolder`), one gap
remained: every image load — thumbnail, owner avatar, both collab avatars,
audio-cover tile — called Glide's `load().apply().into()` chain
unconditionally on **every** `onBindViewHolder`, with no check for whether
the URL had actually changed since the last time this holder loaded it.

`PostDiffCallback.areContentsTheSame()` only compares `likesCount` /
`commentsCount` / `caption` — it never touches the image URLs — so a
live-count-driven or like-tap-driven DiffUtil pass that rebinds a row for
a **non-image** reason still ran every Glide chain on that row from
scratch: Engine key computation, Target attach/detach, and a placeholder
flash while it re-resolves, even though the exact same URL was already
decoded and showing. Glide's own memory cache absorbs the decode cost, but
none of that per-request overhead needed to happen at all for a same-URL
rebind.

## Fix
Added a URL-skip cache per image target on `Holder`
(`lastThumbUrl`, `lastAvatarUrl`, `lastCollabAv2Url`,
`lastAudioCoverSrcUrl`). Each Glide call site now compares the incoming
URL against the holder's last-loaded URL for that target and only invokes
Glide when it actually changed:
- `ivThumb` — gated on `r.effectiveThumbUrl()`
- `ivAvatar` — gated on whichever URL is about to be shown (owner photo,
  or the collab initiator's photo for a collab post — both branches keep
  `lastAvatarUrl` in sync so it always reflects what's actually loaded)
- collab second avatar (`av2`) — gated on `r.collabCollaboratorPhoto`
- `btnAudioCover` — gated on the raw `coverUrl` *before*
  `AvatarUrlBuilder.build()`, so a same-post rebind also skips the resize-
  URL construction, not just the Glide call

`onViewRecycled()` now resets all four caches to `null` alongside its
existing `Glide.clear()` calls — clearing a target drops whatever bitmap
was showing, so the cache has to forget it too, otherwise a future rebind
to a different post that happens to reuse the same image URL would wrongly
skip reloading into a now-empty target.

## File touched
- `PostsFeedActivity.java` — `PostsAdapter.onBindViewHolder()`,
  `PostsAdapter.onViewRecycled()`, `PostsAdapter.Holder`
