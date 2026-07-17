# UPGRADE NOTES — v170: Complete Instagram-like Home Feed

## Summary
Full replacement of the Home Feed tab with a production-grade Instagram-like comprehensive feed system. Upgrades from LinearLayout-based dynamic view inflation to a proper RecyclerView + multi-type adapter architecture.

---

## New Files Added

### Java — feature-reels module

| File | Description |
|------|-------------|
| `feed/HomeFeedAdapter.java` | Multi-type RecyclerView adapter (7 view types) |
| `feed/StoriesBarAdapter.java` | Horizontal stories bar adapter |
| `feed/SuggestedAccountsAdapter.java` | Horizontal suggested accounts adapter |
| `feed/HomeReelsStripAdapter.java` | Mini reels strip adapter |
| `feed/HomeFeedRepository.java` | Firebase data layer (feed, stories, like/save/follow) |
| `feed/StoryViewActivity.java` | Full-screen story viewer (auto-advance, gesture, seen tracking) |
| `models/FeedPost.java` | Unified feed post data model (wraps ReelModel) |
| `models/FeedStory.java` | Story model with sort order |

### Java — Updated

| File | What Changed |
|------|-------------|
| `feed/HomeFragment.java` | **Complete rewrite** — RecyclerView + HomeFeedAdapter, pagination, all actions wired |

### Layouts — feature-reels module

| Layout | Description |
|--------|-------------|
| `fragment_home_feed.xml` | New home feed root layout (RecyclerView + SwipeRefresh + toolbar) |
| `item_feed_photo_post.xml` | Instagram-style photo/video post card (full-width) |
| `item_feed_carousel_post.xml` | Swipeable multi-image carousel post |
| `item_feed_stories_bar.xml` | Stories bar container (RecyclerView) |
| `item_feed_story_thumb.xml` | Individual story circle with ring states |
| `item_feed_suggested_card.xml` | "Suggested for you" horizontal card |
| `item_feed_suggested_user.xml` | Individual suggested user cell |
| `item_feed_reels_strip.xml` | "Reels for you" horizontal strip card |
| `item_feed_mini_reel.xml` | Individual mini-reel thumbnail |
| `item_feed_skeleton.xml` | Loading skeleton at bottom |
| `activity_story_view.xml` | Full-screen story viewer layout |

### Drawables — feature-reels module

| Drawable | Description |
|----------|-------------|
| `gradient_story_ring_active.xml` | Brand gradient ring (unseen stories) |
| `bg_story_seen_ring.xml` | Gray ring (seen stories) |
| `ic_heart.xml` | Heart outline (unlike state) |
| `ic_heart_filled.xml` | Filled heart (liked state, red #EF4444) |
| `ic_bookmark_outline.xml` | Bookmark outline (unsaved) |
| `ic_bookmark.xml` | Filled bookmark (saved, green #4CAF50) |
| `ic_share.xml` | Share/upload icon |
| `ic_comment.xml` | Comment bubble icon |
| `ic_notifications.xml` | Bell/notification icon |
| `ic_add.xml` | Plus/add icon |
| `ic_verified.xml` | Green verified checkmark badge |
| `ic_close.xml` | X / close icon |

---

## Architecture Change

### Before (v6 and earlier)
```
HomeFragment
  └── NestedScrollView
        └── LinearLayout (vertical)
              ├── Stories: dynamically inflated views added programmatically
              ├── Feed: inflated post views added in loop
              ├── Trending: inflated cards added in loop
              ├── Friends Activity: inflated rows added in loop
              └── Suggested: inflated cards added in loop
```
**Problems:** No VH recycling → memory issues with large feeds. All sections loaded upfront. No pagination. Complex view management.

### After (v7 / v170)
```
HomeFragment
  └── SwipeRefreshLayout
        └── RecyclerView (HomeFeedAdapter)
              ├── [pos 0]   StoriesBarVH → StoriesBarAdapter (horizontal)
              ├── [pos 1-N] PhotoPostVH / VideoPostVH / CarouselPostVH
              ├── [pos ~6]  SuggestedVH → SuggestedAccountsAdapter (horizontal)
              ├── [pos ~10] ReelsStripVH → HomeReelsStripAdapter (horizontal)
              └── [pos last] LoadingVH (skeleton + spinner)
```
**Benefits:** Proper VH recycling, O(1) memory for infinite scroll, clean pagination, injectable cards at intervals.

---

## Features Implemented

### Stories Bar
- ✅ 24-hour active stories from contacts and followed users
- ✅ "My Story" always first (with "+" add badge)
- ✅ Sort: My Story → Unseen → Seen → oldest
- ✅ Gradient brand ring for unseen stories (#4CAF50 → #22D3A6)
- ✅ Gray ring for seen stories (#444444)
- ✅ Green ring for my own story
- ✅ Story tap → `StoryViewActivity` (with `Class.forName` fallback to `StatusViewerActivity`)

### Full-Screen Story Viewer (StoryViewActivity)
- ✅ Loads all active items for the owner from `status/{ownerUid}/`
- ✅ Auto-advances every 5 seconds
- ✅ Segmented progress bar
- ✅ Tap left → previous item, tap right → next item
- ✅ Long-press → pause auto-advance
- ✅ Close button (X)
- ✅ Reply bar at bottom with emoji quick reactions
- ✅ Marks story as seen via `statusSeen/{myUid}/{ownerUid}`
- ✅ Edge-to-edge + system bar hiding

### Feed Toggle
- ✅ "Following" / "For You" tabs
- ✅ Animated underline indicator
- ✅ Following feed: reads `user_following/{myUid}` → loads each user's `user_videos/{uid}`
- ✅ FYP feed: reads `reels/videos/` ordered by timestamp desc
- ✅ Own posts filtered from FYP

### Post Actions (all optimistic UI)
- ✅ Like with spring animation (scale 1→1.4→1, 300ms, OvershootInterpolator)
- ✅ Like: Firebase atomic transaction on `reels/reel_likes/{reelId}/{uid}` + `likesCount`
- ✅ Save/Bookmark: `reels/reel_saves/{reelId}/{uid}`
- ✅ Comment: opens `ReelCommentActivity`
- ✅ Share: Android system share intent with reel URL
- ✅ Follow/Unfollow from feed card: `user_followers/{targetUid}/{myUid}` + count transaction
- ✅ More (⋮): popup with "Not interested", "Report", "Follow/Unfollow", "Copy link"

### Per-Post Styling
- ✅ Avatar (CircleImageView with brand border)
- ✅ Verified badge (checkmark icon)
- ✅ Follow button (green → gray when following)
- ✅ Location label
- ✅ Time ago
- ✅ Music bar overlay on media (♪ song · artist)
- ✅ Repost badge (gray bar at top if isRepost)
- ✅ Caption with #hashtag (green) and @mention (teal) coloring
- ✅ Like count + comment count
- ✅ Avatar tap → `UserReelsActivity`

### Carousel Posts
- ✅ Swipeable ViewPager2 inside RecyclerView item
- ✅ Dot indicator overlay (●○○ style)

### Infinite Scroll Pagination
- ✅ 12 posts per page
- ✅ Loads more when 4 items from bottom
- ✅ Skeleton loading at bottom during fetch
- ✅ `lastTimestamp` cursor-based pagination (Firebase `endBefore`)

### Pull to Refresh
- ✅ SwipeRefreshLayout with brand colors
- ✅ Resets all state and reloads from scratch

### Injected Feed Cards
- ✅ "Suggested for you" card every ~6 posts (horizontal RecyclerView)
- ✅ "Reels for you" strip every ~10 posts (horizontal mini-reels)
- ✅ Dismiss button on Suggested card

### Loading States
- ✅ Full-screen spinner on initial load
- ✅ Skeleton + spinner at bottom on pagination
- ✅ Empty state with icon + message

---

## Firebase Paths Used

```
reels/videos/{reelId}               → FYP feed posts (ordered by timestamp)
reels/user_videos/{uid}/            → Per-user posts (Following feed)
reels/user_following/{myUid}/       → Who I follow
reels/user_followers/{uid}/{myUid}  → Follow state check
reels/reel_likes/{reelId}/{uid}     → Like toggle
reels/reel_saves/{reelId}/{uid}     → Save toggle
reels/user_liked_reels/{myUid}/     → My liked posts index
reels/user_saved_reels/{myUid}/     → My saved posts index
reels/users/{uid}/                  → User profiles (name, photo, handle, followerCount)
status/{ownerUid}/{itemId}/         → Story items (< 24h old)
statusSeen/{myUid}/{ownerUid}       → Story seen flag (boolean)
contacts/{myUid}/{contactUid}       → Contacts for story discovery
```

---

## Integration Notes

### ReelsFragment integration (unchanged)
`HomeFragment` is still loaded inside `ReelsFragment.homeContainer` when the
Home tab is selected. The `ReelsFragment` wiring is **unchanged** — only
`HomeFragment` itself is replaced.

### Layout reference change
`ReelsFragment` must inflate `HomeFragment` which now uses `fragment_home_feed.xml`
instead of `fragment_home.xml`. The `fragment_home.xml` file is kept for
backwards compatibility but is no longer the primary layout.

### AndroidManifest
Add `StoryViewActivity` to `feature-reels` manifest:
```xml
<activity
    android:name="com.callx.app.feed.StoryViewActivity"
    android:theme="@style/Theme.Reels.FullScreen"
    android:screenOrientation="portrait"
    android:windowSoftInputMode="adjustResize"
    android:exported="false"/>
```

---

## File Count Impact
- New files added: ~30 (12 Java, 11 layouts, 12 drawables, 1 upgrade notes)
- Modified files: 1 (HomeFragment.java — complete replacement)
- Total project file count: ~2120+

---

*Upgrade authored: July 17, 2026 — v170*
