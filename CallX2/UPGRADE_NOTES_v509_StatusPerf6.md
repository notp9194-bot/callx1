# v509 — Status viewer perf (4: segments, 5: overlapping rendering, 6: mention text, 7: Baseline Profile)

## 4. Progress segments reuse
`buildSegmentBars()` ab `removeAllViews()` + naye ProgressBars nahi banata. Count same ho to koi view create nahi hota;
count badle to sirf tail add/remove hoti hai, last bar ka `marginEnd` fix hota hai, progress 0 reset.

## 5. setHasOverlappingRendering(false)
`ll_top_chrome`, `ll_bottom_chrome`, `ll_reply_bar`, top/bottom scrim par (onCreate). `setChromeHidden()` ka alpha fade ab
offscreen layer nahi banata. Root par nahi lagaya (uska cross-fade backdrop/photo/card overlap karta hai).

## 6. Mention highlight
- `StatusMentionHelper.highlightFast(String)`: '@' nahi to plain String (no Spannable, no regex); '@' ho to cached ranges se naya SpannableString.
- `highlight()` me bhi '@' check + range cache. Viewer ke text-status aur caption `highlightFast` use karte hain.

## 7. Baseline Profile
- `app/src/main/baseline-prof.txt`: StatusViewerActivity (+ `$*` inner), ActivityStatusViewerBinding, StorySpinViewController, StatusMusicClip,
  StatusAudioFocusHelper, Story*Prefs/HintView, StatusBlurTransformation, StatusSeenTracker, StatusMentionHelper, StatusDownloadHelper,
  StatusStickerOverlayView, FloatingReactionOverlayView, MusicTickerView, StatusItem, Replies/Forward sheets (HP = post-startup).
- `CallXBaselineProfileGenerator`: naya `generateStatusViewerFlow()` (Status tab -> viewer -> tap segments -> drag-close).
  Measured profile ke liye device pe chalao: `./gradlew :macrobenchmark:generateBaselineProfile`.
