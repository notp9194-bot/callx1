# v769 — Template Preview (Screen 2) + "New" chip + duration badge

Flow ab: **Templates home -> Preview -> Use Template** (pehle card tap seedha composer kholta tha).

## Naya / badla
- **`TemplatePreviewActivity` (naya, feature-status):** dark stage me animated preview (boxes ek ke baad ek
  light-up hote hain; tap = pause/play), back + bookmark (save) buttons, duration aur music chips.
  Niche white rounded sheet: title, "N uses" (templateStats counter), creator row + **Follow**, "What's inside"
  tiles (Music / Photos / Videos / Duration — templateJson + stickersJson se real data), **Use Template** button.
- **Use Template** abhi bhi `TemplateActions.use` se jata hai, to owner ka reuse-off / removed check pehle jaisa.
  Composer khulne par hi Preview band hota hai (blocked ho to Preview khula rehta hai + toast).
- **Follow:** `FirebaseUtils.setFollowState` (reelFollows/reelFollowers, ek multi-path write). Write fail ho to button revert.
  Apni template par Follow nahi dikhta.
- **Save (bookmark):** `TemplateActions.save/unsave`; result Browser ko wapas jata hai, to hearts aur Saved tab sync rehte hain.
- **`TemplateBrowserActivity`:** card + banner tap -> Preview; "Fun" chip ki jagah **"New"** (newest first);
  har card par **duration** (`0:12 · 3` = duration · photos) jab template me duration ho.
- **`TemplatePreviewDrawable`:** `setFocusPhase()` add hua (sirf Preview animation ke liye; baaki jagah static, pehle jaisa).
- Files: `strings_template_preview.xml` (naya), Manifest me `TemplatePreviewActivity`, `version.properties` 139.
- Sab icons existing drawables hain (ic_bookmark(_filled), ic_arrow_back, ic_timer, ic_music_note, ic_photo_library,
  ic_video, ic_clock_small, ic_sound_flame_outline, ic_status_card_play). Naya artwork nahi.

## Jaan-boojhkar nahi kiya
- **Customize Options (Music/Transitions/Effects/Duration) ke toggles nahi hain** — composer abhi ye choices leta nahi, fake
  button nahi banaye. Unki jagah "What's inside" info tiles hain. Asli customize Editor (v772) me aayega.
- Template ka koi title field nahi hai, isliye title "@name's Template" / "Your Template".
- Template link (`TemplateLinkActivity`) abhi bhi seedha composer kholta hai (Preview nahi).
- Firebase rules / functions me koi change nahi.

## Test
1. Templates home -> kisi card par tap -> Preview khule, boxes animate hon, tap par pause + play icon.
2. "N uses" sahi aaye; Follow/Following toggle ho, screen dobara kholne par state yaad rahe.
3. Bookmark dabao -> back -> Browser me us card ka heart bhara ho; Saved tab me dikhe.
4. Use Template -> composer khule. Owner ne reuse band kiya ho to toast aaye aur Preview khula rahe.
5. Video-slot template: Videos tile me count, duration chip dikhe. New chip: newest pehle.
6. Dark mode me sheet/tiles theek dikhen.

**Build nahi chala** (Gradle yahan nahi hai) — compile karke errors bhej do.
