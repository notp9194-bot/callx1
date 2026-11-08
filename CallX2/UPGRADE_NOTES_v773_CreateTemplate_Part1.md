# v773 (part 1/2) — Create Template (Screen 3): Layout / Music / Effects / Frames

Flow: **Templates home (+) / My Templates > "Create New Template" -> Create Template -> Next -> composer (sample photos) -> Post**.

## Naya
- **`CreateTemplateActivity`** (feature-status): top bar (back, title, ? help), pill tabs **Layout | Music | Effects**, bottom **Next**.
  - **Layout:** 6 layouts (Corner, Duo, Pair, Strip, Polaroid, Column = 1..3 boxes on a full-screen base photo). Har tile asli templateJson se
    `TemplatePreviewDrawable` me draw hota hai, to frame / shape badalte hi tiles badal jate hain. Selected tile par purple border + tick.
  - **Frames:** shape (Square / Rounded / Circle / Heart / Arch) + Border + Shadow toggles (`TemplateBoxStyle`). Circle / Heart ke liye box pixel me square banta hai.
    **Category** chips (Love, Travel, Birthday, Festival, Aesthetic) bhi yahin.
  - **Music:** Trending Audio picker (`ReelTrendingAudioActivity`, class name se) -> cover + title + artist card, Change / Remove, **Music Sync** (`msync`).
  - **Effects:** 6 filters (swatch = wahi colour matrix jo composer / GL shader use karte hain) + 6 animations. Sab boxes par lagta hai (`flt` / `an`).
- **Create mode in composer** (`NewStatusActivity.EXTRA_CREATE_TEMPLATE`): purana "Use template" machinery hi chalta hai (khali boxes, gallery auto-open, smart-fit,
  video boxes, compositor), bas:
  - post hone wala status **khud template** banta hai: `templateJson` = Create screen ka json, music sticker `templateStickersJson` se restore hota hai;
  - `templateFrom*` / "Template by" credit / layout-json nahi lagta, aur **"N used" me koi use record nahi hota**.
- Entry points: `TemplateBrowserActivity` ka + button aur `MyTemplatesActivity` ka "Create New Template" ab Create Template kholte hain.
  Normal composer ka purana **"Make template" pill** jaisa tha waisa hi hai.
- Files: NEW `CreateTemplateActivity.java`, `strings_template_create.xml`. MOD `NewStatusActivity.java` (create mode, 6 chhote edits), `MyTemplatesActivity.java`,
  `TemplateBrowserActivity.java`, `AndroidManifest.xml`, `version.properties` 143.
- Sab icons existing drawables (ic_arrow_back, ic_music_note, bg_music_cover_placeholder). "?" aur tick text se bane hain, naya artwork nahi.

## Jaan-boojhkar nahi kiya (part 2 / baad me)
- **Multi-Scene toggle + scene strip: part 2.** Naya `scenes[]` data model + compositor sequence chahiye, isliye fake toggle nahi lagaya.
- Layouts me base photo hamesha full-screen hai (grid jaise "fit" base layouts nahi): base ka filter / frame abhi engine me hai hi nahi (Editor me bhi sirf boxes).
- Music ka start / end Create screen me nahi: composer me music sticker par long-press se adjust hota hai.
- Drafts tab abhi bhi khali: Create flow draft save nahi karta (part 2 me dekhenge).

## Test (device par)
1. Templates home + -> Create Template khule. Layout tiles me boxes dikhen, tap par border + tick.
2. Frames: Circle / Heart -> tiles me gol / dil boxes; Border / Shadow toggle; category chip.
3. Music: song chuno -> card me cover + naam; Remove; Music Sync switch (song ke bina dim).
4. Effects: B&W swatch grey dikhe; Zoom In chuno.
5. Next -> composer me khali boxes + gallery auto-open + toast "Sample photos chuno". Sab boxes bharo -> Post.
6. Post ke baad My Templates > Created me naya template dikhe (preview me wahi layout / shapes). Dusre account se Use Template -> khali boxes, wahi frame / filter / animation / music.
7. Apne create kiye template ko khud use karo -> counter sirf us waqt badhe (create karte waqt nahi).

**Build nahi chala** (Gradle yahan nahi): sirf bracket-balance + XML validate. Compile error aaye to bhej do.
