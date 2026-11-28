# v928 — Bubble colour polish

Contrast (the main fix)
- `BubbleColorStore.readableOn`: WCAG contrast instead of a plain brightness cut-off. White text is kept while it has >= 3:1,
  otherwise dark text (orange / teal now get dark text, blue / pink / green / violet keep white).
- All overlay colours on a custom bubble are derived ONCE per apply (immutable `Side`), read on the hot path as one deref:
  tick (read / unread), gold accent (audio play button + waveform played part), reply card (bg, bar, sender, text),
  link-card bg (dark card on light bubbles). Default theme returns the original constants unchanged.
- `MessageBubbleCanvasView`: `resolveReplyColors` key now carries `BubbleColorStore.sVersion`; tick bitmap cache is flushed when
  the version changes and is keyed by `onBubble` (media-overlay pills keep stock colours); audio button/waveform/icon use `goldAccent`.
- `ChatThemeManager.getTickColor` is store-aware (covers legacy XML status text too).
- Preview canvas mirrors ticks + gold play button.

Robustness
- `BubbleColorDraft.sInit` / `ensureInit`: Preview never starts from an empty draft after process death; Colour screen keeps its
  draft + tab + side on rotation and opens on the Custom tab when the draft is a non-preset colour.
- Keyboard (IME) insets handled on the Colour screen.

New in the picker
- Hex input (#RRGGBB + Set / keyboard Done) next to the HSV picker, validated.
- Recent colours (last 4 custom colours applied, saved in prefs, shown on the Custom tab).
- TalkBack: swatch grids describe their selection ("Send bubble colour: Violet selected"), announce picks, preset names added.

Other surfaces
- Floating small-window chat (`SmallWindowMessageAdapter`) now uses the custom bubble colour + readable text.
- NOT changed on purpose: feature-x (X DMs / compose) bubbles — separate social surface; per-chat colours (global only for now).

# v929 — Bubble colour: performance pass + refresh fixes

Perf
- Preload: `CallxApp` loads `BubbleColorStore` on a tiny background thread -> first bubble bind never reads SharedPreferences on main.
- `BubbleColorStore.sVersion` is now a plain static int (main-thread written/read; one ldr instead of a volatile read x3 per bind).
- `accent()` has a 2-entry memo per side (reply bar + sender, or the brand colour) -> no pow() after the first call.
- Tick bitmaps: lock-free read path (immutable `TickSet` snapshot, copy-on-write on a miss); the old synchronized builder only runs on a miss.
- Per-view stamp (`boundColorVersion`, set in `sharedBubbleDrawable`): new `PAYLOAD_BUBBLE_COLOR` / `notifyBubbleColorChanged()` /
  `refreshBubbleColors()` skip rows that are already on the current colour version (no paint writes, no invalidate).
  Normal dark-mode / theme toggles still use `PAYLOAD_THEME` -> `refreshThemeColors()` unchanged.

Fixes
- `refreshThemeColors` now also re-applies the audio gold (play button / waveform / icon) — voice rows kept the old gold after Apply.
- `refreshThemeColors` pushes the new text colour into the (shared, cached) text / group-caption StaticLayout paints, because they
  only get their colour at measure time and refresh doesn't re-measure.
