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

## v804i — Circle only during animation + bigger button
- Idle button = arrow only (no disc/ring/glow). On tap the circle fades in (~140ms), plays the same animation, and fades out over the last 200ms, so it never "pops". Both canvas + legacy views (shared `ForwardNeonButton`).
- Button size: canvas `FORWARD_BTN_SIZE_DP` 38 -> 46dp; legacy `ForwardNeonView` 44 -> 54dp (4dp padding, -4dp margin unchanged).

## v804j — ForwardNeonButton per-frame optimization
- Removed both CPU path effects from the hot path: `CornerPathEffect` on `fill` (hit the arrow, 3 trail ghosts, spark circles and head every frame) is baked into the arrow `Path` once via `getFillPath()` (same shape); `DashPathEffect` + `drawArc` for the dotted ring is replaced by one `drawLines()` call of DOTS round-capped dashes (same dash geometry; dots appear as the sweep reaches them).
- Spark trig/size factors are static tables (no cos/sin/toRadians per spark per frame); spark alpha set once per frame.
- Draw path is allocation-free: reusable `dotPts` array, no new objects.
- Idle (arrow-only) stays a single cached-bitmap blit; animation redraw stays limited to the button rect.

## v805 — WhatsApp-style bubble tail (sent + received)
- New `BubbleTailDrawable` replaces the GradientDrawable bubble background. Received: tail pokes out of the TOP-LEFT corner; sent: TOP-RIGHT (WhatsApp). Only the FIRST bubble of a run of consecutive messages from one sender draws it; the rest are fully rounded.
- Perf: the body is a plain `drawRoundRect()` (GPU fast path); the tail-side corner is squared with one `drawRect()`; the tail is one tiny shared `Path` (rounded tip baked once, 1dp overlap with the body = no AA seams) so HWUI caches a single texture for every tail. Drawable is stateless w.r.t. bounds -> 4 shared instances process-wide (sent x tail), nothing rebuilt per row; same day/night + density guard as before.
- Layout: 7dp tail room is always reserved on the sender side for every bubble that draws a background (so all bodies line up); media rows already have 8dp so they don't move. Toggling the tail never re-measures — only the static bubble layer is redrawn.
- Adapter: `isBubbleRunHead()` (1:1 + group, sent + received) sets the tail at bind; `PAYLOAD_BUBBLE_TAIL` + an always-on data observer re-evaluate ONLY the row right after an insert/remove (older page prepended, deleted message) with the cheap payload path. Tuning: `TAIL_W_DP` / `TAIL_H_DP` in `MessageBubbleCanvasView`.
- Bubbleless cards (contact, location, reel, view-once, seen, call entry) are unchanged. The legacy XML bubble path is unchanged.

## v805b — Bubble tail polish
1. Run-head rule: a previous row that draws no tail-capable bubble (contact/location/reel card, call entry, view-once, seen, legacy non-canvas row) now breaks the run (`drawsTailCapableBubble()`), so a run never ends up with no tail at all.
2. Single-media bubbles: the media's top corner on the tail side is squared (`MediaRenderer.squareTailCorner`): after the normal rounded draw only the corner filler (square minus quarter-circle) is painted with the same paint/shader — no per-corner path for the whole image, no alpha doubling on scrims. Media GROUP grids are unchanged.
3. Light bubble colours get a ~10% black 1dp soft edge under body + tail (luminance > 0.6 only; dark bubbles unchanged), drawn once inside the cached static bubble layer.
4. RTL: no change needed — bubble placement is absolute (sent = right, received = left) and the tail follows the same side.
5. Selection tint is row-level (itemView background/alpha), so it already covers the tail.
Not done: tail fade-in/out on neighbor change (would need the dynamic overlay layer for a rarely-visible event); legacy XML rows keep no tail.

## v805c — Bubble tail: bind/draw-path optimization
- Bind: tail flag set via `setBubbleTailForBind()` (flags only — the bind path already resolves the drawable and invalidates; the old setter did it twice on every scroll rebind).
- `sharedBubbleDrawable()`: the two `ContextCompat.getColor()` lookups per bind are skipped while `uiMode` + density are unchanged (one int read); pool is rebuilt only on night-mode/density change.
- Neighbor refresh: `refreshBubbleTailRow()` skips the payload round-trip when the row is bound/visible and its tail already matches; unbound/cached holders are still notified (no stale tails).
- `drawsTailCapableBubble()` uses a String `switch` (cached hash) instead of 7 sequential `equals()`.
- `MediaRenderer.squareTailCorner()`: filler Path rebuilt only when geometry changes (media draws every frame during progress; a mutated Path = a re-rasterised texture).
- Unchanged by design: body = GPU `drawRoundRect`, one shared tail `Path`/texture, 4 shared drawables, soft shadow baked in the cached bubble layer.
