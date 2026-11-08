# v764 — Templates home screen redesign (premium iOS look)

`TemplateBrowserActivity` ab screenshot jaisa home screen hai: white surface, purple accent (#6A3DE8), rounded cards.

## Layout
- Header: purple icon tile + "Templates" + **My Templates** pill (sirf apne templates; dobara dabao to wapas home).
- Search (owner ke naam se filter) + sort button (Most used / Newest / Most photos).
- Chips: Trending (most used) · Popular (discover + saved, most used) · Collage (2+ photo boxes) · Love (jo tumne heart kiye) · Fun (newest first).
- "Trending This Week" banner: top 3 templates, har 4s me auto-rotate, dots tappable, "Use Template" -> `TemplateActions.use`.
- Trending grid: 3 columns, 2 rows, **See All / See Less**. Card = preview + play badge + flame uses pill (1.2K) + heart (save) + owner name + photo count.
- Categories row (Love / Travel / Birthday / Festival / Aesthetic). Love -> Love chip. Baaki: templates me abhi category tag nahi hai, isliye toast.
- Bottom nav: Templates · Saved · (+) · Notifications · Profile. (+) -> `NewStatusActivity`; Notifications/Profile `Class.forName` se (app module me hain).

## Drawables
Saare icons existing project drawables hain, code me tint kiye: ic_video, ic_search, ic_filter, ic_premium_badge, ic_sound_flame_outline,
ic_star_outline, ic_layout_grid_2x2, ic_heart(_filled), ic_emoji, ic_music_note, ic_status_card_play, ic_arrow_forward, ic_home,
ic_bookmark, ic_add, ic_notifications, ic_person. Reels module ke 5 (ic_filter, ic_premium_badge, ic_sound_flame_outline, ic_emoji,
ic_arrow_forward) feature-status/res/drawable me copy kiye (feature-status reels ko depend nahi karta). Koi nayi artwork nahi.

## Strings
`values/strings_template_browser.xml` (naya).

## Data layer
Unchanged (Discover = cached status tray, Saved = savedTemplates/{me}, fetchMeta, save/unsave). Dark theme me bhi chalta hai (colors theme se).

## Notes
- Not compiled / run on device (build tools yahan nahi the).
- Duration (0:15) abhi templateJson me nahi hai, isliye card par photo count dikhta hai.

---
# v765 — Template category tag

- `TemplateCategory` (naya): keys love / travel / birthday / festival / aesthetic, `indexOf(templateJson)`.
- `NewStatusActivity`: "Template options" sheet me **Category** chip row (None + 5). `buildTemplateJson` ab `"cat"` likhta hai (sirf jab chuna ho).
  Firebase rules me change nahi chahiye (templateJson sirf size-validated hai).
- `TemplateBrowserActivity`: Categories tile dabao -> sirf us category ke templates (Discover + Saved dono ke); dobara dabao -> clear.
  Chip / My Templates / Saved dabane par category filter hat jata hai. Category me kuch na ho to empty message.
  "Jaldi aayenge" toast hata diya.
- Purane templates me tag nahi hai, isliye wo kisi category me nahi aayenge (baaki sab jagah dikhte rahenge).
- Not compiled / run on device.

---
# v766 — Video slots, step 1: compositor engine (not wired to the UI yet)

- `TemplateVideoCompositor` (naya, standalone): `compose(under, over, slots, audioPath, audioStartMs, totalMs, W, H, outFile, progress, cancel)`.
  Layers: `under` bitmap -> har video box (centre-crop + zoom/pan, shape mask, rotation, shadow/border frame) -> `over` bitmap (text/stickers).
  H.264 720x1280 @30fps, 4 Mbps. Frame time deterministic (clip trim point se, slot clip se lamba ho to loop). Audio: ek file ka AAC track as-is copy
  (non-AAC audio skip, `Result.audioKept=false`). Background thread se call karo.
- `TemplateBoxStyle`: `maskBitmap(w,h)` + `frameBitmap(w,h)` (box ki shape/shadow/border video ke liye) — baaki code unchanged.
- Koi existing flow nahi chhua: photo templates ka post path bilkul wahi hai.
- Step 2 (baaki): templateJson me slot `k:"video"` + `d` (duration), composer me video box pick + trim, post par under/over bitmaps bana ke compose + `compressAndUploadVideo` path.
- Not compiled / run on device.
