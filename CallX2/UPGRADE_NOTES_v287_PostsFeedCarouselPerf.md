# v287 — Posts feed photo-carousel: Instagram-level swipe performance

Scope: `feature-reels/.../profile/PostsFeedActivity.java` only. No layout,
API, or behavior changes — same swipe/dots/chip/double-tap-like feature
from v285/v286, just the hot path made fast for real scrolling+swiping.

## What was costing performance

1. **New adapter object per bind.** `onBindViewHolder` built a brand-new
   anonymous `RecyclerView.Adapter` for the pager on every single call,
   including rebinds of rows already on screen (recycled back into view,
   or a live-count update elsewhere touching the row). ViewPager2 swaps
   its internal RecyclerView's adapter on `setAdapter()`, which throws
   away all recycled page views — so every rebind re-inflated every
   photo's ImageView from scratch.
2. **New GestureDetector + OnTouchListener per bind.** Same problem,
   smaller object graph, same churn — allocated and torn down every time
   a row scrolled back into view.
3. **No shared recycled-view pool across rows.** Each row's ViewPager2 is
   its own nested RecyclerView with its own pool. On a feed with several
   multi-photo posts, that's N independent pools instead of one shared
   one — the same page view type (a plain centerCrop ImageView) never
   got reused across different posts.
4. **No offscreen preload.** Default `offscreenPageLimit` meant the next
   photo in a swipe wasn't decoded until the swipe was already underway
   — the brief blank/flash frame mid-gesture.

## What changed

- `PhotoPagerAdapter` is now a real (non-anonymous) inner class,
  instantiated **once per Holder** in the constructor and reused for the
  Holder's lifetime. `onBindViewHolder` just calls `setUrls(photoList)`,
  which is a no-op if the same list is already bound and otherwise a
  single `notifyDataSetChanged()`.
- One `RecyclerView.RecycledViewPool` shared across every row's pager
  (`photoPagerSharedPool`, capped at 8 recycled pages) — set on each
  pager's inner RecyclerView once, at Holder-construction time.
- `photoPager.setOffscreenPageLimit(1)` — the adjacent photo is bound
  and decoded before the user's swipe reveals it.
- Double-tap-to-like's `GestureDetector`/`OnTouchListener` are built once
  in `Holder.setupPhotoPagerOnce()` and read a mutable
  `photoPagerBoundReel` field instead of closing over a `final` post
  reference rebuilt every bind.
- Carousel pages decode via a dedicated `pagerPhotoOpts` using
  `DecodeFormat.PREFER_RGB_565` (half the memory of ARGB_8888) — a
  multi-photo post can have the current + preloaded-neighbor page
  decoded at once, so this caps peak memory during a swipe.
- `PhotoPagerAdapter.onViewRecycled` clears each page's Glide target the
  instant it scrolls off, same discipline the outer feed already applied
  to `iv_post_thumb`/`iv_post_avatar`.
- Inner pager RecyclerView's item animator disabled (uniform ImageView
  pages don't need fade/change animations fighting the swipe).

## Unchanged (verified)

- Single-photo posts: pager/dots/chip stay GONE, static `iv_post_thumb`
  path untouched.
- Dot indicator and "pos/total" chip visuals/update logic — same as
  v285/v286, still live-tracks `onPageSelected`.
- `h.photoPager.setCurrentItem(0, false)` reset on every bind — still
  there, so a recycled row never opens mid-carousel.
