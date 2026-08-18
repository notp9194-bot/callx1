# v84 — Home feed: ultra-fast, butter-smooth scrolling

Purely a performance pass on the Home tab feed. No feature behaviour was
changed: every existing card listener, the shared-ExoPlayer architecture, the
v83 scrub bar / hold-2x / resume / autoplay work, ranking, pagination and the
Continue-Watching wiring all behave exactly as before.

Home's feed is a `NestedScrollView` + `LinearLayout` with one permanent view
per post, so unlike a `RecyclerView` nothing is ever recycled and the cost of
a frame grows with the length of the session. Everything below attacks that.

## 1. Detach-virtualization (biggest structural win)
`HomeFeedWindowManager` already unloaded bitmaps for cards >2 screens away,
which bounds *memory* — but the views themselves stayed in the tree, so the
container's measure/layout/draw traversal kept growing with every appended
page. Cards further than **4 screens** away are now swapped out of the tree
for a spacer `View` of exactly the same height:

- scroll geometry is unchanged, so there is no jump;
- the card `View` object is *kept*, only detached — all listeners, like/save
  state and player binding survive, which is what makes this safe here;
- swaps only ever run from `onScrollSettled()`, never mid-fling;
- the currently playing card is pinned via `setProtectedView()` and can never
  be detached out from under the player.

## 2. Idle-time card pre-inflation — `HomeFeedCardPool` (new)
Inflating `item_home_feed_post` (~40 views incl. `PlayerView`) is the single
most expensive thing the feed does, and it used to happen on the exact frame
the card was needed. The pool inflates up to 4 cards ahead via a
`MessageQueue.IdleHandler`, i.e. only when the main thread has nothing else
queued, so it never competes with a scroll. `AsyncLayoutInflater` was
deliberately not used — `PlayerView` is not safe to build off the main thread.

## 3. Card rendering now yields to the user's finger
The old staged renderer posted every remaining card up-front on a fixed 16 ms
ladder, so those inflations landed in the middle of the first fling. It is now
a self-chaining drain that skips frames while `isFeedScrolling`, bounded to
~0.5 s of yielding so a long fling can never outrun pagination. Paginated
pages are *appended* to the in-flight drain instead of starting a second one
(which used to cancel the first and silently drop its remaining cards), and
the drain is cancelled on refresh / mode-switch / `onDestroyView`.

## 4. Zero-allocation scroll listener
`onScrollChange` fires on every scrolled pixel. It previously allocated two
`Runnable`s per event and re-computed `dpToPx` thresholds; both are now
created once. `playMostVisibleCard()` reuses one `int[2]`, skips detached
cards, and stops walking the list at the first card below the viewport
instead of measuring every card in a long feed. Pagination/prefetch math is
skipped entirely when scrolling upward.

## 5. Prefetch de-duplication
`prefetchUpcomingFeedMedia()` was called once per scrolled pixel while the
viewport sat in the prefetch band, re-dispatching the same Glide preloads and
the same byte-range video preload hundreds of times per fling. It now runs
once per new frontier index.

## 6. Decode-size fixes
- Card thumbnail: `720x720` → `540x960`, matching the card's 9:16 frame
  (~44 % less bitmap memory, nothing decoded then cropped away).
- Avatar: was decoding the full-resolution profile photo into a 36dp view;
  now overridden to 144 px.
- The card load, the prefetch and `HomeFeedWindowManager`'s reload now all use
  the **same** override. Glide keys its cache on requested size, so the
  previously mismatched sizes (720x720 vs 720x1280) meant the prefetch warmed
  an entry the card never read and the bitmap was decoded twice.

## 7. Hardware-layer correctness
The scroll-time `LAYER_TYPE_HARDWARE` promotion is skipped once the content
root exceeds 4096 px (a few cards in), since GPUs refuse a layer that large —
all that was left was the cost of re-rendering the subtree on every layer
flip, i.e. exactly the stutter it was meant to remove. Scroll-state tracking
is now independent of whether the layer was actually applied.

## 8. One less overdraw pass
`item_home_feed_post`'s root repainted `@color/background_light` even though
`fragment_home`'s root already paints it — a full extra overdraw pass over the
whole feed area, every frame. Removed.

## Files
- new: `feature-reels/src/main/java/com/callx/app/feed/HomeFeedCardPool.java`
- `feature-reels/src/main/java/com/callx/app/feed/HomeFeedWindowManager.java`
- `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`
- `feature-reels/src/main/res/layout/item_home_feed_post.xml`

## Not done
As requested, no Gradle build and no runtime/on-device testing was performed —
static review only.
