# v16 — Colorful touches (all bind-time / constructor-time Paint.setColor, zero perf cost)

All four changes below are pure `Paint.setColor()` swaps — no measure/layout
touched, no new allocations, no per-frame work. Two are set once in the
`MessageBubbleCanvasView` constructor (forward button, forwarded label);
the other two are set inside the existing per-bind color block
(`bindPoll()`, `bindAudio()`), same place `textPaint`/`tickPaint` already
get their colors every bind.

## 1. Poll — leading option now always visually distinct
Before: the leader-accent fill (`POLL_OPTION_FILL_LEADER`) only showed up
when the leading option was *also* your own vote — otherwise it rendered in
the same flat indigo as every other option, so "who's winning" wasn't
visible at a glance.

Now: `POLL_OPTION_FILL_LEADER` is a distinct green (`0x662FA843`) and
applies to the leading option regardless of whether it's your vote; a
matching `POLL_LEADER_STROKE_COLOR` (`0xFF2FA843`) borders it. Your own
vote still gets priority (green voted-stroke) if it happens to also be the
leader, so nothing double-renders in two different greens.

## 2. Audio waveform — played portion gets the brand teal
Before: idle and played bars were both just the bubble's text color at
different alpha (90 vs 255) — monochrome, easy to miss which part had
played.

Now: played bars use `AUDIO_BTN_BG_COLOR` (`0xFF008069`, the same teal as
the play/pause button) — reads clearly against light-green/white
(light theme) and dark-teal/charcoal (dark theme) bubbles alike, and ties
visually to the play button.

## 3. Quick-forward button icon
Flat grey (`0xFF757575` icon / `0x14000000` bg) → brand teal
(`0xFF008069` icon / `0x1F008069` bg), matching the audio accent above.

## 4. Forwarded label ("↪ Forwarded from X")
Flat grey (`0xFF888888`) → soft brand-teal tint (`0xFF4A9B8E`) — stays
readable/muted (still italic, still small) but no longer looks like
disabled/greyed-out text.

## What was deliberately left alone
No bubble-background gradient, no reaction-badge recolor, no link-preview/
file-card accent changes this pass — those need an actual visual mock to
get right rather than a guessed hex value, and weren't asked for
specifically. Happy to do any of those next with a concrete color
reference.
