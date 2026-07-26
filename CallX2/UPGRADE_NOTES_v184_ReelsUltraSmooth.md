# UPGRADE_NOTES v184 — Reels Ultra-Smooth Playback

## What changed

This release hardens the active full-screen Reels path around the three
performance problems in the previous build:

### 1. Thumbnail flash / black shutter removed

- Media3 `PlayerView` now uses a transparent shutter.
- The last rendered surface content is retained when the player is rebound.
- The thumbnail stays above the player until `Player.Listener.onRenderedFirstFrame`.
- The handoff uses an 80 ms alpha crossfade, then removes the thumbnail.
- `STATE_READY` and `isPlaying` no longer hide the thumbnail prematurely.

This means a player being ready is not treated as proof that a decoded frame is
already visible. The decoded-frame callback is the single handoff point.

### 2. Scroll rendering simplified

- Removed the full-page `ReelPageTransformer` from the active feed.
- Removed the per-vsync `ReelChoreographerSnapSync` RecyclerView walk from the
  active feed.
- ViewPager2 now stays on its native full-page snap path.
- The inner RecyclerView uses fixed-size rows, a one-item cache, no item
  animator, no overscroll, and no nested scrolling.

The photo slideshow's own transformer is unchanged; it is a separate,
small-surface photo interaction.

### 3. Playback coordination is bounded

- `controlPlayback()` only checks the current page and its two immediate
  neighbours.
- `pauseAllReels()` and the chat-dock handoff use the same bounded window.
- Predictive player prewarm checks only N+1, because that is the only speculative
  fragment that can exist with `offscreenPageLimit=1`.
- Repeated visibility requests no longer restart listeners and animations for a
  fragment that is already in the requested state.

## Expected result

- No one-to-two-frame black flash during the thumbnail-to-video transition.
- Lower GPU composition work during vertical swipes.
- No O(feed-size) fragment walk on normal page changes or playback handoff.
- Lower main-thread work and more stable frame pacing on long feeds.
- Existing cache, ABR, player-pool, offline fallback, listener visibility, and
  N+1 prewarm behavior remain intact.

## Validation note

The source was statically reviewed against the project's Media3 1.2.1 APIs and
the active Reels call path. A local Gradle compile could not be run in the
packaging environment because no Java runtime was installed.