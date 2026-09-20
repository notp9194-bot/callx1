# v432 — Reels long-press → Playback Options sheet

Long-press on a video reel ab hold-to-pause ki jagah ye sheet kholta hai (playback chalta rehta hai):

| Row | Kaam |
|---|---|
| View fullscreen | 3-dot menu wala **Cinema Mode** (`uiController.toggleCinemaMode()`), ON hone pe label "Exit fullscreen" |
| Speed | .5x / 1x / 1.5x / 2x → `ReelPlayerController.setSpeedIndex()` (same `SPEED_STEPS` jo `cycleSpeed()` use karta hai) |
| Auto scroll | ON → reel end pe next reel pe swipe (loop nahi). `ReelPlaybackPrefs` mein persist. Last reel pe loop hi chalta hai |
| Closed Captions | Stream ke text track ko select/disable karta hai. Pref persist; har naye reel pe `applyCaptionsPreference()` |

## Files
- NEW `feed/ReelPlaybackOptionsSheet.java`, `res/layout/bottom_sheet_reel_playback_options.xml`, `core/.../ReelPlaybackPrefs.java`
- `ReelUiController.onLongPress` → `delegate.showPlaybackOptionsSheet()` (photo-mode reels ka apna hold-to-pause same)
- `ReelPlayerDelegate` +2 methods; `ReelPlayerFragment` implements sheet `Listener`
- `ReelLoopSeekHelper.setLoopInterceptor()` (auto scroll hook), `ReelsFragment.advanceToNext()` ab `boolean`
- `ReelPlayerController`: `setSpeedIndex`, `applyCaptionsPreference`, `hasCaptionTracks`, `onTracksChanged`

## Drawables reused (koi naya drawable nahi)
`bg_more_sheet`, `bg_drag_handle`, `bg_more_item_ripple`, `bg_pill_dark_solid` (speed container),
`bg_pill_dark_translucent` (selected speed), `ic_display_mode_immersive`, `ic_speed`, `ic_repost`, `ic_caption`;
theme `ReelMoreBottomSheetTheme`.

## Note
Reels ke saath caption data store nahi hota (editor ka `subtitles_json` upload pe kahin save nahi hota), isliye CC tab kaam karega
jab stream mein text track ho; warna toast "this reel has no captions".
