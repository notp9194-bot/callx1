# v454 — Chat header + input bar: real glassmorphism

All header icons (back, reel, X, YouTube, voice call, video call, ⋮ menu) are now
glass plates with a LIVE backdrop blur of the wallpaper/messages behind them,
plus saturation boost, tint, top gloss, and a bright rim. Applies to 1-to-1 and
group chat (both inflate `activity_chat.xml`).

## New
- `chat/ui/GlassHeaderLayout` — header container; paints all plates in one pass
  (one blur for all buttons). Set via `app:glassSource="@id/fl_chat_content"`.
- `chat/ui/GlassImageButton` — icon-only button; attrs `glassTint`,
  `glassCornerRadius`, `glassInset`.
- `chat/ui/GlassRenderNodeBackdrop` — API 31+ RenderNode + RenderEffect blur (GPU).
- `res/values/attrs_glass.xml`

## Changed
- `activity_chat.xml`: content FrameLayout got `@+id/fl_chat_content`; toolbar is
  `GlassHeaderLayout`; the 7 ImageButtons are `GlassImageButton` (ids unchanged).
  Reel/X/YouTube keep brand colours as tinted glass.
- Header background is now a soft top-down scrim instead of a solid bar
  (`ChatThemeController` + `GroupChatActivity.applyScreenTheme` call `applyScrim`).

## Behaviour
- API 31+: GPU blur. API 23–30: 1/8-scale bitmap capture + box blur, throttled
  (~20 fps); if capture fails it falls back to tinted frosted fill.
- Backdrop refreshes in a pre-draw listener without `invalidate()` → no extra
  frames when the chat is idle.

Not built or tested here (no Android SDK) — please build and check on device.

## Input bar (v454b)
The whole input capsule (`ChatInputBarContainer`, id `cv_input_capsule`) is now one
glass pill: live blur of the messages/wallpaper scrolling behind it + tint, gloss,
rim. `bg_chat_input_capsule` is no longer used by the layout (file left in place).
Enabled with `app:glassSource="@id/fl_chat_backdrop"` (styleable `GlassInputBar`).

- New shared engine `GlassBackdrop` (blur capture, API31 RenderNode / <31 bitmap),
  used by both `GlassHeaderLayout` and `ChatInputBarContainer`.
- New `GlassCapsuleSkin` paints the big pill (cached shaders, no per-frame allocs).
- Layout: wallpaper + skeleton + `rv_messages` are wrapped in `fl_chat_backdrop`.
  This is the ONLY blur source — it must never contain a glass view, otherwise the
  header/input RenderNodes would reference each other. (Replaces the earlier
  `fl_chat_content` id, which also contained the input bar.)
- Recording bar / mic-lock etc. draw over the glass unchanged.
