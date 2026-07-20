# v35 — Community System: Full Canvas Rendering Migration

## Summary
All community system list rows are now rendered via custom Canvas views,
matching the chat module's MessageBubbleCanvasView architecture established
in v32. Every RecyclerView in the community feature now bypasses XML inflate
and the full View-hierarchy measure/layout pass.

## What Changed

### New Canvas Views (feature-chat/.../community/canvas/)
| Canvas View | Replaces |
|---|---|
| `CommunityNotificationCanvasView` | `item_community_notification.xml` |
| `CommunityJoinRequestCanvasView`  | `item_community_join_request.xml` |
| `CommunityMemberCanvasView`       | `item_community_member.xml` |
| `CommunityMemberSearchCanvasView` | `item_community_search_result_member.xml` |
| `CommunityScheduledPostCanvasView`| `item_community_scheduled_post.xml` |
| `CommunityModerationLogCanvasView`| `item_community_moderation_log.xml` |
| `CommunityEventCanvasView`        | `item_community_event_v2.xml` |

### Updated Adapters (all in feature-chat/.../community/)
- `CommunityNotificationAdapter`   — canvas migration
- `CommunityJoinRequestAdapter`    — canvas migration + Glide CustomTarget for avatar
- `CommunityMemberAdapter`         — canvas migration + Glide CustomTarget for avatar
- `CommunityMemberSearchAdapter`   — canvas migration + Glide CustomTarget for avatar
- `CommunityScheduledPostAdapter`  — canvas migration
- `CommunityModerationLogAdapter`  — canvas migration
- `CommunityEventAdapter`          — canvas migration + Glide CustomTarget for cover image

### Already migrated (v32/v33)
- `CommunityPostAdapter` + `CommunityPostCanvasView` (main feed posts) ✅

## Performance Benefits
- **Zero XML inflate** per row: no LayoutInflater, no View tree construction
- **Zero measure/layout pass** per child: onMeasure computes all geometry
  once; onDraw paints directly — same speed advantage as chat bubbles
- **Glide asBitmap() + CustomTarget** for all image-bearing rows: avatars
  and cover images decoded at exact on-screen pixel size (override()),
  PREFER_RGB_565 halves per-pixel memory for non-alpha images
- **BitmapShader cache** in avatar-bearing views (Member, JoinRequest,
  MemberSearch): shader rebuilt only when Bitmap reference or target rect
  size changes — no per-frame GC pressure during scroll flings
- **onViewRecycled()** clears in-flight Glide loads on all adapter VHs
  that use Glide targets, consistent with CommunityPostAdapter

## Architecture Pattern (same as chat canvas)
```
Adapter.onCreateViewHolder()  →  new XCanvasView(ctx)   (no inflate)
Adapter.onBindViewHolder()    →  cv.bind(entity)         (set data)
                                 Glide.asBitmap()→CustomTarget→cv.setXBitmap()
XCanvasView.onMeasure()       →  compute all RectF geometry
XCanvasView.onDraw()          →  canvas.drawXxx() only
XCanvasView.onTouchEvent()    →  hit-test RectFs, fire Listener
```

## XML Layouts Retained (no canvas equivalent needed)
The following XML layouts are kept as-is since they are used by Activities
(not RecyclerView rows) or have complex widget interactions better served
by the View system:
- `activity_community_*.xml`, `fragment_community_*.xml`
- `item_community_post.xml` (legacy — superseded by canvas in v32, kept for reference)
- `item_community_comment.xml` (comment thread rows — future migration)
- `item_community_group.xml` (group chip rows — simple, low scroll volume)
- `item_community_discover.xml` (discovery cards — low scroll volume)
- `item_community_bookmark.xml` (bookmark list — low scroll volume)
- `item_community_rule.xml` (static content)
- `item_community_media_grid.xml` (used by CommunityCarouselAdapter/ViewPager2)
