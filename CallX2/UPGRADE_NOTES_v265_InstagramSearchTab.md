# v265 — Instagram-style Explore + Search tab

Added a dedicated Search tab between Reels and Status in the main bottom
navigation. The tab uses the existing Reels grid/player and Firebase user
records instead of a separate mock data source.

## Included flow

- Three-column live Explore grid populated from `reels`.
- Search bar with Meta AI-style placeholder and back/clear controls.
- Recent-search screen with ordered local history, per-row remove, and “See all”
  clear-all behavior.
- Debounced people search across display name and CallX ID.
- Hashtag matching from reel metadata.
- Caption, creator, music, and hashtag reel matching.
- Reel suggestions and matching reel grid with handoff to the existing
  `SingleReelPlayerActivity`.
- People results open the existing `UserProfileActivity`.
- Existing Chats, Reels, Status, Groups, and Calls tabs retain their original
  routes and badge behavior after the new tab is inserted.
- Search tab hides the unrelated floating action button for a cleaner explore
  surface.
- Long-pressing any Explore or filtered reel thumbnail reuses the existing
  `ReelPeekPreviewController` mini video player, including playback, mute,
  scrim dismissal, and Watch Reel handoff. A normal tap still opens the full
  reel player directly.