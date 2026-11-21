# v804 — Neon quick-forward button (chat media bubble side button)
- New `ForwardNeonButton` (feature-chat/.../conversation/canvas): dark disc + glowing violet ring + white curved share arrow, same look as the reference screenshot.
- Tap = 1.6s animation (9-frame timeline: solid ring → shock-wave + spark → ring turns to dashes → dotted ring → sparkle burst → arrow dash with motion trail/speed lines → arrow hits ring edge → solid ring, arrow back).
- Real forward action fires at `ForwardNeonButton.ACTION_AT_MS` (1400ms, arrow launch frame). Change that constant to fire earlier/later. Extra taps while animating are ignored.
- `MessageBubbleCanvasView`: button moved from the cached static bubble into `drawDynamicOverlayLayer()` so the animation redraws without re-recording the bubble; idle look is a cached bitmap blit (no per-frame cost). Animation + pending action cancelled on detach / recycle (no wrong-message forward).
- Button size 30dp -> 38dp, margin 0.
- Legacy View path (`item_message_sent/received.xml`): `ic_forward_neon` vector + scale/spin animation, then `onForward`.

## v804b — Light-mode fix
- `ForwardNeonButton` is now theme-aware (`draw(..., dark)`, `staticBitmap(size, dark)`): dark mode = unchanged neon look; light mode = light lavender disc, deeper violet ring/glow/sparks/trail, deep-violet arrow + stronger glow so every effect stays visible on light wallpapers. Animation timeline untouched.
- Idle bitmap cache is keyed by size + mode. `MessageBubbleCanvasView.drawForwardButton()` passes `ChatThemeManager.isDarkMode()`.
- Legacy View path: `drawable/ic_forward_neon.xml` is now the light variant, `drawable-night/ic_forward_neon.xml` keeps the dark one.

## v804c — Polish pass
1. ~~Snappier forward~~ — REVERTED: `ForwardNeonButton.ACTION_AT_MS` stays 1400ms (original timing).
2. **Touch feedback:** canvas button scales to 0.88 while pressed (cancelled on MOVE-out/CANCEL/UP); touch target = button + 5dp slop on top/bottom/outer side (never toward the bubble). Legacy view uses `setPressed`.
3. **Reduce-motion:** if system animator scale is 0 (`ForwardNeonButton.motionReduced`), animation is skipped and forward fires immediately (canvas + legacy).
4. **Partial invalidate:** animation redraws only the button rect (+40% margin for glow/sparks/trail) instead of the whole bubble; press feedback also invalidates only that rect.
5. **Accessibility:** canvas bubble exposes a custom "Forward" TalkBack action (id = `R.id.btn_quick_forward`, forwards immediately, no animation); legacy button keeps contentDescription "Forward".
6. **Legacy path parity:** new `ForwardNeonView` (extends ImageButton, reuses `ForwardNeonButton`) replaces the old spin animation in `item_message_sent/received.xml` (44dp + 4dp padding, -4dp margin so position is unchanged). `MessagePagingAdapter` uses `play(action)` / `cancel()` on rebind.
7. **Light mode sparkles:** 1.35x bigger sparks in light palette.

## v804e — Light-mode disc size
- Geometry was already identical (disc radius = R + 0.4*sw in both modes); the light disc just blended into the light wallpaper and looked smaller. Light disc is a bit deeper lavender and gets a thin violet edge at the exact same radius, so the visible circle size matches dark mode.

## v804f — Cached dark flag
- `isDarkMode()` is no longer called per draw: `MessageBubbleCanvasView` and `ForwardNeonView` cache it in `onAttachedToWindow()` and refresh it (plus invalidate) in `onConfigurationChanged()`.

## v804g — Chat perf micro-caches
1. `MessageBubbleCanvasView.spToPx()` caches `scaledDensity` (reset in `onConfigurationChanged`).
2. New `TextWidthCache`: `measureText()` results cached per (text, textSize) for expiry pill, playing badge, call entry (icon/label/dot) and read-more strip — no per-frame measuring.
3. `MessagePagingAdapter`: call-duration labels and the video duration overlay use the cached `formatVoiceDuration()` instead of per-bind `String.format()`. (Call labels now always use ASCII digits, same as the voice/video labels.)
4. `bindPoll` text color uses the memoized `ChatThemeManager.getTextColor()`. (`getTextColor`/`getTickColor` for normal bubbles were already memoized/constant.)
Not changed: per-bind `isDarkMode()` (cheap, once per bind) and location-address `String.format` (rare bind).

## v804h — Secondary-list optimizations
1. `CommunityPostCommentsActivity`: Comment/Reply/Mention adapters use DiffUtil (item = id/uid, content = every rendered field incl. reactions + nested replies) instead of `notifyDataSetChanged()`. Recycled comment rows now reuse their nested `ReplyAdapter` + LayoutManager instead of allocating new ones on every bind.
2. `MentionSuggestAdapter`: `setItems()` / `filter()` diff by uid (runs every keystroke). `RecentMediaGridAdapter.submit()`: diff by uri (camera-tile offset handled); `setCellSize()` keeps the full refresh (every cell changes size).
3. `LinkPreviewFetcher`: title regex is a static constant; meta-tag regexes compiled once per property and cached.
4. `GroupMemberAdapter.formatLastSeen()`: cached per-locale `SimpleDateFormat`s instead of constructing one per bind.
Behaviour is unchanged; these only reduce rebinds/allocations.
