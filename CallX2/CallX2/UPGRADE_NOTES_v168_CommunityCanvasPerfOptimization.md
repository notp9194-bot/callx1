# v168 — Community Feed Canvas: Ultra-Advanced Scroll Performance Optimization

Scope: `feature-chat/.../community/canvas/*`, `CommunityPostAdapter.java`,
`CommunityFeedFragment.java`. No behavior/visual changes — same pixels, same
click regions, same bind() contract. Pure perf.

## Problem

`CommunityPostCanvasView` (+ its 7 *Renderer classes) already draws the whole
feed post on Canvas instead of inflating `item_community_post.xml` — good
architecture, but every `draw()` call was still allocating fresh objects:
`BitmapShader`, `Path` (card clip, media clip, play-triangle, heart, arrow),
`RectF`. During a scroll fling with 8-12 visible rows redrawing every frame,
that's dozens of allocations/frame → GC churn → dropped frames.

On top of that, avatar/media images were decoded by Glide at **original
resolution** (`CustomTarget` with no `.override()` defaults to
`Target.SIZE_ORIGINAL`), and nothing ever canceled an in-flight Glide load
when a row got recycled mid-fling.

## What changed

**1. Zero per-frame allocation in draw paths**
- `CommunityPostCanvasView`: card background now `drawRoundRect()` directly —
  removed the `clipPath()+drawRect()+restore()` (clipPath is one of the
  costliest Canvas ops) since nothing actually needed the clip.
- Added host-level caches: `avatarShaderCache`, `mediaShaderCache`,
  `mediaGroupShaderCache[4]`, `mediaClipPath`, `mediaGroupClipPath[4]`,
  `playTrianglePath`, `mediaGroupTrianglePath[4]` — each rebuilt only when
  its actual input (bitmap identity / rect size) changes, not every frame.
- `AuthorHeaderRenderer`, `PostMediaRenderer`, `PostMediaGroupRenderer`:
  reuse the cached shader/clip/triangle instead of `new BitmapShader(...)` /
  `new Path()` per draw.
- `EngagementBarRenderer`: heart + share-arrow `Path`s cached and just
  translated via `canvas.translate()` instead of rebuilt from scratch.
- `PostPollRenderer`: the per-option vote-fill bar reuses one `RectF` field
  instead of `new RectF()` per option per frame.
- `bind()` / `bindMediaGroup()` reset the shader caches so a rebound row
  never draws a stale post's bitmap through a leftover shader.

**2. Glide loading — decode-size + cancellation**
- Added `CommunityPostCanvasView.avatarPx()` / `.mediaHeightPx()` static
  helpers; `CommunityPostAdapter` now applies `RequestOptions.override(w,h)`
  sized to what's actually drawn (was implicitly `SIZE_ORIGINAL` before —
  a 4000×3000 camera photo was being fully decoded for a 40dp avatar).
- Added `DecodeFormat.PREFER_RGB_565` (avatars/media here don't need alpha —
  halves per-pixel memory vs default ARGB_8888).
- `CommunityPostAdapter.VH` now holds `avatarTarget`/`mediaTarget`
  references; `onBindViewHolder` clears the previous target before starting
  a new load, and `onViewRecycled` clears both — so a fast fling cancels
  in-flight decodes for rows that have already scrolled away instead of
  letting them finish and get silently discarded.

**3. RecyclerView tuning**
- `CommunityPostCanvasView`'s constructor allocates ~45 `Paint` objects (it
  replaces what used to be an inflated CardView/LinearLayout tree). Default
  RecyclerView pool only retains 5 scrap views per type, so fast flinging
  through a long feed was re-running that setup repeatedly. Bumped the pool
  to 24 for the feed's single view type.
- `LinearLayoutManager.setInitialPrefetchItemCount(4)` for smoother nested/
  shared-pool prefetch when this feed sits in a ViewPager2 tab.

## Not changed
- Layout math, hit-testing, click regions, DiffUtil payload logic — untouched.
- `PostTextRenderer`'s `StaticLayout` caching was already correct (only
  rebuilds on text/width change) — left as-is.
