# v803 — Cold start lean (WhatsApp-style fast open)
- CallxApp: only Room warm-up, channels, Glide, top-chats preload stay on the critical path. WorkManager schedules, E2E key listeners, video/ExoPlayer caches, privacy sync, CameraX + ThumbHash warm-ups, cache-system listeners run in `app-init-deferred` (BACKGROUND priority) 2.5s after process start.
- Init threads now BACKGROUND priority so they stop competing with the UI thread.
- UnifiedVideoCacheManager + Reel/XTweet/StatusVideo managers self-initialise on first use (no crash if Reels/Status open inside the 2.5s window).
- Splash hold 1200ms -> 500ms.
- startup-prof.txt added (approximate, subset of baseline-prof.txt). Regenerate measured: ./gradlew :macrobenchmark:generateBaselineProfile
- Already fine, unchanged: ViewPager2 creates only the Chats tab at launch; MainActivity already stages avatars/badges/FCM after first frame.
