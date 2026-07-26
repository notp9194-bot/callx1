# v226 — Link (🔗) & Add Yours (➕) Status Stickers

## What changed

Added two new Instagram-style status stickers to the existing sticker system
(alongside Music, Countdown, Quiz, Question, Poll, Slider, Mention, Hashtag):

- **🔗 Link** — poster pastes/types a URL (scheme auto-normalised to
  `https://` if missing) with an optional custom label. Rendered as a
  high-contrast white pill (unlike the other dark cards) showing the label,
  or the link's host name if no label was given. A viewer tapping it fires a
  plain `ACTION_VIEW` intent to open it in the browser — no reflection
  needed since it's a system intent, not an in-app screen.

- **➕ Add Yours** — poster sets a prompt (e.g. "My study era 📚"), with
  four quick-fill suggestion chips in the creator dialog. Rendered as a
  bordered card with a ➕ icon, "ADD YOURS" header, and the prompt text.
  A viewer tapping it (only if they aren't the poster) opens
  `NewStatusActivity` pre-loaded with the same sticker so they can post
  their own story continuing the chain — both activities live in
  `feature-status`, so this is a direct class reference, not reflection.

  The chain's origin is preserved across repeated taps: each sticker JSON
  carries `originUid`/`originName`. The very first post leaves these blank;
  when a viewer adds their own, `StatusViewerActivity` fills them in with
  either the tapped sticker's existing origin (if this status is itself
  already a chain reply) or the current poster (if this status IS the
  origin) — so a 5-deep chain still credits whoever started it, not just
  the immediately-previous poster. The rendered card shows
  "↳ Started by {name}" once an origin is present.

## Files touched

- `feature-status/.../stickers/StatusStickerPickerSheet.java` — two new
  grid cards + creator dialogs (URL+label input / prompt input with
  suggestion chips).
- `feature-status/.../stickers/StatusStickerOverlayView.java` — two new
  `build*()` renderers + `fromJson` dispatch + getters (`getLinkUrl()`,
  `getLinkLabel()`, `getAddYoursPrompt()`, `getAddYoursOriginUid()`,
  `getAddYoursOriginName()`).
- `feature-status/.../viewer/StatusViewerActivity.java` — tap wiring:
  `openLinkSticker()` (ACTION_VIEW) and `openAddYoursComposer()` (launches
  `NewStatusActivity` with the prefill extras below), matching the existing
  pause/resume-around-navigation pattern the other stickers already use.
- `feature-status/.../compose/NewStatusActivity.java` — new public extras
  `EXTRA_PREFILL_STICKER_JSON` / `EXTRA_PREFILL_TOAST`, consumed by the new
  `applyPrefillStickerIfAny()` (called right after `restoreDraft()` in
  `onCreate`) to auto-attach the incoming Add Yours sticker once the
  overlay frame is laid out.
- `NewStatusActivity.getStickerAddedLabel()` — added friendly toast labels
  for `link`/`addyours` (also backfilled `poll`/`slider`, which were
  missing their own labels and were silently falling through to the
  generic "✨ Sticker").

## Also fixed (found while implementing this)

While drafting the `link`/`addyours` JSON-building lines in
`StatusStickerPickerSheet.java`, one intermediate draft accidentally
double-escaped the quotes (`\\"` instead of `\"`), which would have
produced literal backslash characters inside the JSON payload instead of
plain quotes. Caught and corrected before packaging by diffing against
every other sticker type's JSON-building line in the same file, which all
use single-backslash escaping.

