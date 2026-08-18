# v83 — Reels Home tab feed: advanced inline playback (full flow)

Scope: the Reels **Home** tab feed (`HomeFragment` + `item_home_feed_post.xml`).
No build was run — source-only upgrade.

## What was missing before

The Home feed autoplayed the most-visible card and nothing else:

* Inline playback was invisible to the rest of the app — a reel watched
  end-to-end in the feed never incremented `viewsCount`, never landed in
  `reelWatchHistory`, and never wrote `reelWatchProgress`. Home's own
  "Continue Watching" strip therefore only ever filled from the full-screen
  player.
* There was no timeline: no progress indicator and no way to scrub.
* There was no way to pause a card, and no fast-forward.
* `users/{uid}/feedSettings/autoplay` ("Always" / "Wi-Fi Only" / "Off") was
  written by `ReelFeedSettingsActivity` but **read by nobody**, so choosing
  "Off" or "Wi-Fi Only" changed nothing in the feed.

## What was added

### 1. Watch tracking + resume — `HomeFeedWatchTracker` (new)

Writes to the *same* Firebase paths the full-screen player uses, so both
surfaces share one history:

| Path | When |
|---|---|
| `reelViews/{reelId}/{uid}` + `reels/{reelId}/viewsCount` transaction | after a 3 s dwell, once per viewer per reel (guarded by the existing marker, so it cannot double-count against the player) |
| `reelWatchHistory/{uid}/{reelId}` = timestamp | after a 5 s dwell — this is what "Continue Watching" reads |
| `reelWatchProgress/{uid}/{reelId}` = 0–100 % | while playing, throttled to one write / 4 s; reset to 0 past 95 % (same "finished" convention as the player) |

Dwell timers are cancelled if the user scrolls away first, so a card that
flashed past mid-fling never counts.

Read side: the whole progress map is fetched **once** per feed load, so a
resume lookup per card is an in-memory hit rather than a Firebase read.
A reel left between 5 % and 90 % resumes where it was, once per session.

### 2. Autoplay preference — `HomeFeedAutoplayPolicy` (new)

Reads `users/{uid}/feedSettings/autoplay` and re-evaluates it against the live
connection each time a card becomes active (so walking out of Wi-Fi range
stops autoplay on the next card, not on the next app start).

* `Always` — unchanged behaviour.
* `Wi-Fi Only` — autoplays only on Wi-Fi/Ethernet/unmetered.
* `Off` — never autoplays; the card shows a centre play button.

Important: **pre-buffering is not gated** — only the decision to actually
`play()` is. Tap-to-play stays instant. A card the user deliberately paused
also stays paused across `onResume()` / tab switches instead of being
force-restarted.

### 3. Scrubbable inline progress bar

`sb_post_progress` pinned to the bottom of each video card, reusing the
full-screen player's `progress_reel_seekbar` / `thumb_reel_seek` drawables.
Expressed in permille (max 1000) because one bar outlives several media items
as the shared player hops between cards. Thumb is invisible at rest and fades
in on drag; `tv_post_position` shows `m:ss / m:ss` while scrubbing. Dragging a
card that isn't the active one promotes it first, so the seek lands on the
right media.

### 4. Tap play/pause and press-and-hold 2×

Both were folded into the **existing** double-tap-to-like `GestureDetector` —
a View has only one `OnTouchListener`, so a second listener would have
silently killed double-tap-to-like.

* single tap (confirmed) → play/pause, and the way to start a card under
  "Off";
* long press → 2× playback with a `2x ▶▶` chip + haptic, restored to 1× on
  finger up **or** cancel;
* double tap → like (unchanged).

### 5. Progress ticker

A 250 ms ticker on the existing `scrollHandler` drives the scrub bar, the time
label and the throttled progress writes. It is started/stopped with playback
and torn down on pause, tab-hide, feed reload and `onDestroyView` — and the
current position is flushed (throttle bypassed) before the active card loses
its player in any of those paths, so scrolling away never loses a resume
position.

## Files

* new `feature-reels/.../feed/HomeFeedWatchTracker.java`
* new `feature-reels/.../feed/HomeFeedAutoplayPolicy.java`
* mod `feature-reels/.../feed/HomeFragment.java`
* mod `feature-reels/res/layout/item_home_feed_post.xml` — scrub bar, time
  label, 2× chip, centre play button; the mute button's bottom margin was
  raised to 34dp so the new scrub strip does not swallow its taps.

No new dependencies, no new drawables (existing player drawables reused), no
Firebase schema additions.
