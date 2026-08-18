# v53 — Bio Links Strip: Per-Chip Color Fix

## Bug
In `UserReelsActivity`, the bio-links chip row (`hsv_bio_links` / `ll_bio_chips`)
used a single shared field `profileBioStripColorHex` + `applyBioStripAccentColor()`
that looped over **every** chip and painted them all with the same color.
So picking a color on one chip (e.g. Instagram) also recolored Website,
YouTube, and Twitter chips.

## Fix
- Each bio link is now tagged with a **stable type key** (`website`,
  `instagram`, `youtube`, `twitter`) built alongside it in
  `UserReelsActivity` where the links list is assembled.
- Colors are now stored per-chip in a map (`profileBioChipColorsMap`) and
  persisted to Firebase at:
  `reels/users/{targetUid}/profileBioChipColors/{typeKey}`
  (previously: a single `profileBioStripColor` value for the whole strip).
- Long-pressing a chip now opens the same rainbow picker but:
  - pre-fills it with **that chip's own** current color (not the strip's),
  - on pick, writes only to that chip's Firebase node,
  - recolors **only that one `TextView` instance**, not the whole
    `LinearLayout`.
- New helper `buildChipDrawable(hex)` always returns a **fresh, unshared**
  drawable instance per call, so no `GradientDrawable`/mutated drawable is
  ever reused across chips (a subtle source of accidental "everyone shares
  one color" bugs if a template were cached and reused).

## Backward compatibility
Old installs may already have a value under the legacy
`reels/users/{uid}/profileBioStripColor` node from before this fix. That
value is still read (into `profileBioStripColorHex`) and used as a
**one-time fallback default** for any chip that doesn't yet have its own
entry in `profileBioChipColors`. It is never written to again — once a
user long-presses any chip after this update, that chip gets its own
entry and the legacy field becomes irrelevant for it.

## Files changed
- `feature-reels/src/main/java/com/callx/app/profile/UserReelsActivity.java`
  - Field: `profileBioStripColorHex` repurposed as legacy-only fallback;
    added `profileBioChipColorsMap`.
  - `onDataChange(...)`: links now carry a 4th "type key" element; reads
    new `profileBioChipColors` map from Firebase.
  - `buildBioChips(...)`: applies each chip's own starting color at
    build time (no longer a shared post-loop pass); long-press now passes
    `(typeKey, chipView)`.
  - Replaced `applyBioStripAccentColor(hex)` (looped over all children)
    with `applyChipAccentColor(chip, hex)` (single view only) +
    `buildChipDrawable(hex)` helper.
  - `openBioStripColorPicker()` now takes `(typeKey, chipView)` and
    reads/writes per-chip instead of strip-wide.

## Not touched
The **profile-song strip** (`layoutProfileSong` / `layoutAddSongStub`,
`profileSongStripColorHex`, `applyStripAccentColor`) is a separate,
single pill — untouched, still works exactly as before.
