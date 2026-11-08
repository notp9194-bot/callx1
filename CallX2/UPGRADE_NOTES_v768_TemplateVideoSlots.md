# v768 — Template video boxes, step 2: UI wired (base: v767)

v766 ka `TemplateVideoCompositor` ab composer se jud gaya. Photo templates ka path bilkul wahi hai (video box na ho to koi code nahi chalta).

## Poster (template banane wala)
- "Template options" sheet me naya **Box type**: har box ke liye Photo / Video, video box ke liye length chips (2s / 3s / 4s / 5s / 8s / 10s).
- `buildTemplateJson`: video box par `k:"video"` + `d` (ms) likhta hai (`TemplateSpec.Slot.putJson`), root par `tdur` = sabse lambe video box ki length. Photo box ka JSON pehle jaisa hi.
- Poster ki apni photo video box me sirf layout dikhane ke liye rehti hai.

## Viewer ("Use this template")
- Video box par photo ki jagah video icon (`ic_video`) wala "+" dikhta hai. Tap → **video picker** (gallery se 1 video).
- Video app cache me copy hota hai (`cache/tpl_video/`), pehla frame box me box ki shape/frame ke saath dikhta hai.
- Clip box ki length se lamba ho to **Trim sheet** khulti hai: preview frame + start slider ("Start 0:03 → 0:07"), "Change video", "Done". Chhota clip box me repeat hota hai. Filled video box par tap = dobara trim sheet.
- "Auto-fill" pill ab sirf photo boxes bharti hai (video boxes ko clip chahiye); picker se multi-photo pick bhi video box me photo nahi dalta.

## Post
- `dispatchPost`: template + koi video box → `prepareAndUploadTemplateVideo`:
  1. photo boxes + backdrop ek bitmap me bake (video boxes snapshot se hidden, `snapshotHideExtra`),
  2. `TemplateVideoCompositor.compose(...)` — 720 x (canvas aspect) mp4, har clip apne box me (shape / rotation / frame), progress hint "Preparing video… N%",
  3. mp4 normal `compressAndUploadVideo` se upload (type "video").
- Text / music / stickers pehle ki tarah JSON overlays rehte hain. Music sticker ho to mp4 silent; nahi ho to pehle video box ki awaaz (sirf AAC audio).
- Scheduled post, template credit, "N used" counter — sab downstream `saveStatus` se, unchanged.

## Files
MOD compose/NewStatusActivity.java (fields, picker, trim sheet, compose+upload, options sheet, buildTemplateJson, auto-fill guard), version.properties (138).

## Test (device par)
1. Poster: 2+ photo boxes ka template banao → Template options → Box 2 = Video, 4s → post. Dusre account se "Use this template".
2. Box 2 par tap → video chuno → (lamba ho to) trim sheet → Done. Box 1 me photo. Post → ek video status banna chahiye, dono boxes (photo + chalta video) sahi shape me.
3. Music sticker wala template → video status silent mp4 + music sticker bajta hai. Bina music → clip ki awaaz.
4. Chhota (2s) clip, 4s box → repeat. Non-AAC audio wala clip → silent video (crash nahi).
5. Video box khali ho to Post: "Pehle sabhi photo boxes bharo".
6. Photo-only template → pehle jaisa photo status.

## Dhyan / baaki
- Not compiled / run on device (Gradle nahi tha). Compositor GL/MediaCodec path pehli baar asli device par chalega — slow ya green frame aaye to logcat tag `TplVideoComp` bhejna.
- Video box me zoom/pan nahi (sirf trim start). Box length poster tay karta hai, viewer badal nahi sakta.
- Poster ki apni template ko khud post karne par status photo hi rehta hai (usme video nahi hota).
- Browser/preview card par video badge + duration abhi nahi (`TemplateSpec.displayMs()` ready hai, screen 1/2 me lagega).
