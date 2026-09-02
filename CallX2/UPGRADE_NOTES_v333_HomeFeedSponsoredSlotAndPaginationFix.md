# v333 — Home Feed: inline sponsored slot + pagination boundary fix

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## Correction to the earlier "what's not Instagram-level" review
A prior review of this feed (based on the v243/v244 upgrade notes and a
shallow scan of `HomeFragment.java`) concluded the ranking was "just a
heuristic with no real personalization or feedback loop." That was wrong —
it missed `core/.../ranking/FeedRankingEngine.java` and `RankingProfile.java`,
which the current `HomeFragment.loadFeed()` already calls. That system
already does real per-user personalization from actual watch history:
implicit hashtag affinity, hashtag fatigue, media-type affinity, creator
affinity, discovery/exploration slots, diversity re-ranking, freshness
boost, explicit "Not Interested"/preferred-topic filtering, AND a
server-side re-rank pass (`ReelFeedRankingClient.rank`, `POST /reels/rank`)
layered on top using permanent server-side watch history. None of that
needed rebuilding — rebuilding it here would have just been a worse
duplicate of code that already exists and is already wired in.

Two things from that review held up under closer inspection and are what
this pass actually addresses:

## 1. Inline sponsored/ad slot (was genuinely absent)
There was no ad-mixing mechanism anywhere in the codebase (confirmed —
no "sponsor"/"ad" hits outside this patch). Added, following the exact
same interleaving pattern the codebase already uses for
`ROW_SUGGESTED_CREATORS` / `ROW_SUGGESTED_REELS`:

- New `ROW_SPONSORED` row type + `SponsoredAd` model (deliberately
  separate from `ReelModel` — an ad never enters `currentFeedPosts`,
  `FeedRankingEngine`, watch-history tracking, or the like/comment/repost
  pipeline).
- `insertSponsoredRowIfDue()` — every `SPONSORED_EVERY_N_POSTS` (9) organic
  posts, For-You mode only (Following stays pure chronological, same rule
  as the other inline rows). Reads a flat `sponsoredReels` Firebase node
  once per session (cached in `sponsoredAdPool`, same caching shape as
  `suggestedCreatorPool`) and round-robins through it via
  `sponsoredAdCursor` so a short pool still fills every slot on a long
  scroll.
- `bindSponsoredRowContent()` — builds the ad card programmatically
  (avatar + sponsor name + "Sponsored" label + image + headline + CTA),
  same "bare FrameLayout, build content at bind time" approach
  `bindNewPostsBannerContent()`/`bindSuggestedCreatorsRowContent()` already
  use, so no new layout XML was needed.

**Honest scope note**: this is real, additive feed-mixing plumbing, not an
ad-network/SDK integration. There's no impression/click billing pipeline —
that's a backend/ads-network task, not something client-side code can
stand in for. What's real here: the actual on-screen slot, cadence, and
rendering an ad SDK's response would drop into; wire a real ad source into
`sponsoredReels` (or swap the Firebase read for an SDK call) and the rest
already works.

## 2. Pagination boundary bug (`loadMoreFeedPosts`)
Old cursor: `.endAt(oldestFeedTimestamp - 1)`. Two reels can legitimately
share the same millisecond timestamp; that cursor permanently excluded any
such reel from every future page once the boundary moved past it, even if
it had never actually been rendered — a silent, permanent gap.

Fixed by making the cursor inclusive (`.endAt(oldestFeedTimestamp)`) and
relying on the `renderedReelIds` de-dupe that already existed at that call
site — nothing renders twice, but nothing gets permanently skipped either.
Also fixed a related stuck-pagination case this surfaced: if an entire
page came back 100% duplicates at the boundary timestamp, the old code
never advanced `oldestFeedTimestamp`, so the next scroll-triggered call
would re-issue the identical query forever. The cursor now advances from
the raw page's oldest timestamp regardless of how much of it was filtered,
and steps back one ms as a fallback if a page is duplicates-only.

## Not touched
Ranking, RecyclerView/ViewHolder pooling, ExoPlayer standby pool, watch
tracking, real-time listener, everything from v243/v244/v323 — all
untouched, still working as before.
