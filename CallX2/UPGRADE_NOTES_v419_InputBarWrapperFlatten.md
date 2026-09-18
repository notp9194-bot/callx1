# Upgrade v419 — Chat Input-Bar Wrapper Flattening

## What changed

- Replaced the chat input hierarchy
  `floating stack -> input row -> MaterialCardView -> ConstraintLayout`
  with one `ChatInputBarContainer`.
- The custom container lays out the View Once button, message field, and
  merged `ChatIconBarView` directly in one measure/layout pass.
- Preserved the capsule appearance with a lightweight shape drawable.
- Kept the recording bar behind the existing lazy `ViewStub`; recording now
  hides only the normal input controls while the container remains in place.
- Updated both 1:1 and group chat recording/theme paths to use the flattened
  container.

## Scope

This upgrade only targets input-bar wrapper/layout overhead. No app build or
automated test was run, as requested.