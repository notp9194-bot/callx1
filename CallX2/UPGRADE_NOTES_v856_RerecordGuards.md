# v856 — Re-record guards (on top of v855)

Static audit of every plain `invalidate()` (which marks the outer bubble cache dirty). No Gradle/SDK here — run a normal build.

- **Reply thumb fade removed** (`startReplyThumbFade`): the 160ms animator did a full-bubble re-record every frame (~10 per reply thumb). Thumb now appears directly; one record from `setReply()`.
- **Single image/video** (`setMediaBitmap`): same bitmap instance + same placeholder flag now returns early (aspect ratio must already be known, so the first real bitmap still primes it).
- **Progressive download partials**: new `setMediaBitmapPartial()` (adapter: 4 call sites) drops partials arriving <140ms after the previous one when a real bitmap is already shown. Final bitmap from `onReady` is never throttled.
- **Album cell bitmap** (`setMediaGroupBitmap`): same-instance guard.
- **Same-instance guards**: reel thumb/avatar, contact avatar, seen avatar/thumb, location map. Reel username: same-value guard.
- Not changed (content really changes): bind, first real bitmap swap, tail flip, name-row flip, reply/pin/edit set, read-more, link preview, theme, first reactions/seen-by, local-availability rebind (`checkLocalAvailabilityAsync` -> full rebind once per media row).

Verify: in a trace, `Bubble.record` count while receiving a reply-with-thumb message and while downloading a single photo should drop. Numbers not measured.
