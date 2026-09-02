# v334 — Home Feed: fixed thumbnail→video black-flash

## Root cause
`PlayerView.setShutterBackgroundColor(TRANSPARENT)` was set on the Reels tab
(`ReelPlayerController`) but **never** on the Home Feed's `pvFeed`
(`HomeFragment.FeedViewHolder`). Media3's default shutter is opaque black and
sits under the thumbnail (`ivThumb`) in z-order — it's invisible while the
thumbnail is fully opaque, but the moment `onRenderedFirstFrame()` fires and
the thumbnail starts its crossfade-out, any scheduling gap between "shutter
cleared" and "thumb alpha starts dropping" exposed a frame (or more, on a
busy main thread) of solid black. That reads as: thumbnail disappears, THEN
video appears — exactly the "thumbnail hatta h fir video play hoti h" gap,
even though the crossfade logic itself (`revealCardThumbnailAfterFirstFrame`,
PTS-gating, hardware-layer promotion) was already correct and already
matched the Reels tab.

## Fix
Set `pvFeed.setShutterBackgroundColor(Color.TRANSPARENT)` once, at
ViewHolder construction (`FeedViewHolder` ctor, next to the `pv_feed_post`
`findViewById`). Now the surface behind the thumbnail is always transparent,
so there is nothing to flash — the crossfade the code already does becomes
the *only* visible transition, matching Reels tab / Instagram: continuous
image, no gap.

## Scope
One line + comment, `HomeFragment.java` only. Reels tab was already correct
and is untouched. No behavior change to autoplay, preloading, PTS gating, or
the crossfade timing/duration logic — those were already sound.

## Why this is the real fix, not the crossfade code
The crossfade path (thumbnail → alpha 0 over the decoded frame) was already
functionally identical to Instagram's approach and to this app's own Reels
tab. The visible bug was a rendering layer underneath it that Reels tab
happened to neutralize and Home Feed didn't. Re-writing the crossfade logic
again would not have fixed anything — the shutter is what needed to change.
