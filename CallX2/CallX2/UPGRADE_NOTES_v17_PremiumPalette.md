# v17 — Premium palette (deep-emerald + champagne-gold)

Token system (named hex):
- **Emerald `#0F4C3A`** (light) / **`#145C46`** (dark) — brand_primary: toolbar,
  input bar, send/mic FAB, reply accent, forward-button icon.
- **Deep emerald `#0A3327`** — brand_primary_dark / gradient end.
- **Champagne gold `#D4AF37`** — brand_accent. This is the one signature
  element, used *only* for "this is the premium/standout state" moments:
  read-receipt ticks, the audio waveform's played-portion + play button,
  and a poll's leading-option accent. Same gold everywhere so it reads as
  one deliberate identity, not scattered recolors.
- **Sent bubble**: soft emerald-tinted ivory `#E7F0EA` (light) /
  deep emerald `#123D30` (dark) — replaces the stock WhatsApp
  lime-green/teal.
- **Received bubble**: warm ivory `#FFFDF8` (light) / near-black charcoal
  `#181C20` (dark) — slightly warmer than the old pure-white/cool-charcoal.

## Where it landed
- `colors.xml` / `values-night/colors.xml`: brand_primary(_dark), brand_accent,
  brand_gradient_start/end, bar_background, bubble_sent(_end),
  bubble_received(_end), forward_icon_tint.
- `ChatThemeManager`: `getPrimaryColor()`/`getSecondaryColor()` → emerald/gold;
  `getTickColor(true)` → gold (was WhatsApp blue `#34B7F1`) — the signature.
- `MessageBubbleCanvasView`: `AUDIO_BTN_BG_COLOR` → gold (play button +
  waveform played-portion share this one constant); forward-button icon/bg →
  emerald; `POLL_OPTION_FILL_LEADER`/`POLL_LEADER_STROKE_COLOR` → gold
  (leading option now reads as a "winner" cue); `FORWARDED_LABEL_COLOR` →
  muted gold tint.
- Left alone on purpose: `POLL_VOTED_STROKE_COLOR`/`POLL_OPTION_VOTED_BG`
  (still green/indigo) — that's the separate "this is your vote" signal,
  and keeping it distinct from the gold "leader" signal means a
  mine-and-leading option shows both cues instead of one color doing two jobs.

## Perf note
Every change here is either an XML color-resource value or a `static final
int` Paint-color constant — no new fields, no per-bind/per-frame logic, no
allocations. Same zero-cost guarantee as the v16 pass.
