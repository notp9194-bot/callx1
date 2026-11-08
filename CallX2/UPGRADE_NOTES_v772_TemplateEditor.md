# v772 — Template Editor (Screen 4) + filter / animation engine

## Naya
- **`TemplateEditorActivity`** (owner only): preview card (selected box highlight), **Slot tabs**, **Filter**, **Animation**, **Duration** slider (1–15s, 0.5s step),
  **Total length** (Auto / 0.5s–60s) aur **Music Sync**. "Apply to all slots" switch filter/animation sheet me. Back par unsaved-changes dialog.
- Khulta hai: My Templates > Created > **⋮ > "Template edit karo"**. Save ke baad row ka preview/json turant update hota hai.
- **Save kahan:** `status/{me}/{id}/templateJson` (agar status abhi live hai) aur `savedTemplates/{me}/{me}_{id}` (agar snapshot hai). Rules me change nahi.
  Sirf optional keys likhta hai: `d`, `flt`, `an`, `tdur`, `msync` — box geometry/shape/music/text ko haath nahi lagata. Default values JSON me likhi hi nahi jaati.
- **`TemplateEffects`** (naya): 6 filters (natural, warm, cool, bw, vivid, fade) = ek colour matrix, jo editor swatch, composer aur GL shader teeno use karte hain.
  6 animations (none, zoom in/out, pan left/right, fade in) = slot ke progress ka function.

## Jo ab asal me lagta hai (jab koi template use kare)
- **Filter:** photo box me turant (composer me live dikhta hai + post me bake), video box me GL shader se.
- **Animation:** photo ya video box me **animation ho to post video status banta hai** (photo box ab compositor ka "image clip" hai, backdrop me bake nahi hota).
  Bina animation wale photo-only template pehle jaise photo status hi rahte hain.
- **Duration:** har box utni der dikhta hai, phir backdrop dikhta hai (sab boxes ek saath shuru hote hain — sequential scenes v773 ke multi-scene me aayenge).
- **Total length:** explicit set ho to wahi video length (lambe box usi par cut), "Auto" = sabse lamba box.
- **Music Sync:** music sticker ho to video ki length = viewer ke rakhe music clip ki length (max 60s). Beat-detect nahi hai — jaan-boojhkar, fake nahi kiya.

## Files
NEW `TemplateEditorActivity.java`, `TemplateEffects.java`, `strings_template_editor.xml`.
MOD `TemplateVideoCompositor.java` (image slots, colour matrix + alpha uniforms, per-frame zoom/pan, OES + 2D shader), `NewStatusActivity.java`
(slotSpec har box ke liye, filter in `renderTemplateSlot`, `templateSlotWindow`, animated-photo → video path, music-sync length, explicit total),
`TemplateActions.java` (owner sheet me optional "edit" row), `MyTemplatesActivity.java` (editor launch + result), `AndroidManifest.xml`, `version.properties` 142.

## Test (device par)
1. My Templates > Created > ⋮ > Template edit karo → Slot tabs, Filter/Animation/Duration badlo → Save → wapas aao; row preview ok. Dobara kholo → values yaad.
2. Back bina save → dialog (Save / Discard / Edit karte raho).
3. Dusre account se us template ko use karo: Filter "B&W" → box black-white (composer me live). Post → status me bhi B&W.
4. Animation "Zoom In" → post "Preparing video…" dikhaye, status video bane, box dheere zoom ho. Pan / Fade bhi.
5. Video box + filter → GL shader (dhyan se dekho: rang theek, green frame nahi).
6. Total length 5s, box 10s → video 5s. Music Sync on + music sticker → video music clip jitna.
7. Template jisme box nahi (sirf base) → sirf "Poora template" card dikhe.

## Dhyan / baaki
- **Compile nahi hua** (Gradle yahan nahi): sirf javac se syntax check kiya, symbol resolution Android SDK ke bina nahi ho sakta. Build me typo aaye to error bhej do.
- Compositor ab 2 shader programs banata hai (OES clips + 2D photos); photo clip pehli baar device par chalega — green/black box ho to logcat tag `TplVideoComp` bhejo.
- **Drafts tab nahi banaya:** editor live template ko in-place edit karta hai, adhura template rakhne ki jagah nahi — Drafts hamesha khali rehta. Create flow (v773) ke saath draft ka matlab banega.
- Editor sirf apne template ke liye hai. Base (poori) photo ka filter/animation abhi nahi — sirf boxes.
- Filter swatch preview editor me hai, par stage preview me photos nahi hote (template ki sirf geometry hai), isliye stage par filter/animation nahi chalta.
