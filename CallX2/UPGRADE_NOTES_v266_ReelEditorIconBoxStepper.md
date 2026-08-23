# v266 — Reel Editor: icon-box step stepper (screenshot match)

Replaced the plain numbered-dot stepper on the Reel Editor (Trim/Text/Look/
Motion & Sound/Finishing) with an icon-box stepper matching the provided
reference screenshot exactly.

## What changed
- Each of the 5 steps is now a dark rounded-square icon box (crop / T /
  palette / equalizer bars / check) instead of a plain numbered circle.
- A small solid-pink number badge overlaps the bottom edge of every box —
  always the same brand pink regardless of step progress.
- An always-visible uppercase label sits under every box (bold/white once a
  step is reached or passed, dim while still upcoming) — previously the step
  name only appeared once, in the header above the stepper.
- The connecting lines between boxes are now a static brand pink→purple
  gradient across the whole row (previously they filled in progressively;
  the screenshot shows the same gradient in every step, so this was
  simplified to match).
- Only the currently-active step's box gets a gradient border ring. The old
  spinning orange halo ring is gone — it was a circular ring behind what's
  now a rounded-square box and didn't read correctly; it's now a static
  border overlay (`bg_step_icon_box_active`) shown/hidden per step instead.

## New files
- `feature-reels/.../drawable/ic_editor_text.xml` — "T" glyph (Step 2)
- `feature-reels/.../drawable/ic_editor_look.xml` — palette glyph (Step 3)
- `feature-reels/.../drawable/ic_editor_motion.xml` — plain white equalizer
  bars (Step 4) — `ic_waveform_mini` wasn't reused since it hardcodes
  brand pink/purple strokes and wouldn't read as a clean white glyph here
- `feature-reels/.../drawable/ic_editor_finish.xml` — outline circle +
  checkmark (Step 5) — `core`'s `ic_check_circle` wasn't reused since it's a
  filled white disc built for a small colored badge elsewhere
- `feature-reels/.../drawable/bg_step_icon_box.xml` — dark rounded-square
  fill, same for all 5 boxes
- `feature-reels/.../drawable/bg_step_icon_box_active.xml` — gradient
  border ring shown only on the active box
- `feature-reels/.../drawable/bg_step_badge.xml` — solid pink circle for
  the number badge
- `core/.../color/step_icon_box_bg`, `step_badge_bg`
- `core/.../string/editor_step_label_1..5`

## Touched files
- `activity_reel_editor.xml` — `editor_stepper` block rebuilt as 5
  icon-box columns + 4 gradient connector lines (same view IDs reused:
  `step_ring_1..5`, `step_dot_1..5`, `step_line_1..4`; new
  `step_label_1..5`)
- `ReelEditorActivity.java`
  - `EDITOR_STEP_NAMES[0]`: `"Trim and Crop"` → `"Trim & Crop"` (matches
    header + screenshot)
  - new `editorStepLabels` field + findViewById wiring
  - `updateEditorStepDots()`: now only toggles label color (bold/white vs
    dim) — badge + line styling moved to static XML
  - `updateActiveEditorStepRing()`: now just shows/hides a static border
    per step instead of animating a spinning ring
