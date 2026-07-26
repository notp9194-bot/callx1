# v225 — Mention (@) & Hashtag (#) Status Stickers

## What changed

Added two new Instagram-style status stickers to the existing sticker system
(alongside Music, Countdown, Quiz, Question, Poll, Slider):

- **👤 Mention** — poster types a username, sticker shows `@username` on the
  status. A viewer tapping it resolves the username to a uid (Firebase
  `users` node, same `orderByChild("username")` lookup pattern already used
  for chat mentions) and opens that user's profile (`UserProfileActivity`,
  loaded via reflection since `feature-status` has no compile-time
  dependency on `app`).
- **#️⃣ Hashtag** — poster types a topic, sticker shows `#topic` on the
  status. A viewer tapping it opens the topic's feed in the X module
  (`XHashtagActivity`, loaded via reflection since `feature-status` has no
  compile-time dependency on `feature-x`) — same screen the X tab's hashtag
  search already uses.

Both are static display stickers (no live vote/quiz state to sync), so
there's no new Firebase write path — just a tap-to-navigate action, same
shape as the existing Music sticker's tap-to-open-sound-sheet.

## Files touched

- `feature-status/.../stickers/StatusStickerPickerSheet.java` — two new
  grid cards + creator dialogs (username / topic text input).
- `feature-status/.../stickers/StatusStickerOverlayView.java` — two new
  `build*()` renderers + `fromJson` dispatch + getters
  (`getMentionUsername()`, `getHashtagTag()`).
- `feature-status/.../viewer/StatusViewerActivity.java` — tap wiring:
  `openMentionStickerProfile()` and `openHashtagStickerFeed()`, both
  pause/resume the story progress bar around the navigation, matching the
  existing music-sticker pattern.

## Also confirmed (no change needed)

Checked the "add a second status shows the first one" complaint —
`StatusListAdapter.java`'s carousel `+` badge (`ivAddBadge`) already routes
to `onAddStatusClick` (→ `NewStatusActivity`) independently of the
row/card tap (→ `StatusViewerActivity`), even once `myStatuses` is
non-empty. This WhatsApp-style fix is already in place in this codebase
(see the `// WhatsApp-level fix` comment in `StatusCardAdapter`). If this
still reproduces on-device, it's most likely a stale/old APK build rather
than a code issue — reinstall from this zip to confirm.
