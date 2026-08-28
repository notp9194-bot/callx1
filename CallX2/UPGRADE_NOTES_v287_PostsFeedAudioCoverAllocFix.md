# v287 — Post tab (PostsFeedActivity) scroll hot-path allocation fixes

Target: `feature-reels/src/main/java/com/callx/app/profile/PostsFeedActivity.java`
(`PostsAdapter.onBindViewHolder` + `getPreloadRequestBuilder`).

The adapter already had several PERF passes (cached `thumbOpts`/`pagerPhotoOpts`,
once-built gesture detectors, cached carousel dot views). This pass closes the
remaining per-bind allocation sources found in the same method.

## Fixed

1. **Audio-cover tile (the reported one, ~line 1459)** — `btnAudioCover`'s Glide
   call built a brand-new `RequestOptions` + `MultiTransformation(CenterCrop,
   RoundedCorners)` object graph on every single bind of any row with music
   attached. Size (28dp·2 retina) and corner radius (4dp) are fixed constants,
   never per-post — cached once as adapter field `audioCoverOpts` and reused via
   `.apply(audioCoverOpts)`. This is the single biggest fix in this pass: on a
   feed where most rows carry a music track, that's a full transformation
   object graph allocated per row on every scroll pass.

2. **Owner avatar (every row, every bind)** — `.diskCacheStrategy(ALL).circleCrop()`
   allocates a fresh `CircleCrop` transform per bind. Cached as `avatarOpts`.

3. **Collab dual-avatar row** — `RequestOptions.circleCropTransform()` (a static
   factory that itself allocates) was called up to twice per bind for collab
   posts. Cached once as `collabAvatarOpts`.

4. **`getPreloadRequestBuilder`** — `ListPreloader` calls this repeatedly *during
   scroll* for upcoming off-screen rows, so it's on the hot path too, not just
   `onBindViewHolder`. It built a `RequestOptions` identical to `thumbOpts` on
   every call; now reuses the same cached `thumbOpts` instance (also makes the
   preloaded decode share Glide's cache key with the real bind).

## Not changed (already fine)

- `String.valueOf(...)` / `formatCount(...)` calls for likes/comments/reposts —
  unavoidable per-bind text formatting, not worth caching (no chain of objects,
  just a String each frame the row is *bound*, not every scroll frame).
- Dot indicator, gesture detectors, photo-pager adapter — already cached from
  a prior pass (see inline comments in `Holder`/`PostsAdapter`).

## Net effect

Zero new `RequestOptions`/transformation objects allocated per bind on the
Post tab's scroll hot path — every Glide call in `onBindViewHolder` now
`.apply()`s a pre-built, adapter-level-cached `RequestOptions` instance,
matching the approach Instagram's own feed adapters use (options structs
built once, reused across every row/rebind).
