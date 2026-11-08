# v773 (part 2/2) — Multi-Scene template

Flow: **Create Template > Layout tab > Multi-Scene ON > har scene ka layout + length chuno > Next > composer me har scene ki sample photos > Post**.
Viewer: **Use Template > Scene strip (1 / 2 / 3) > har scene ke boxes bharo > Post** -> scenes ek ke baad ek chalte hain (ek mp4).

## Data model (`TemplateSpec`, JSON "v":1 hi rehta hai)
```
{ "v":1, "slots":[scene 0 ...], "scenes":[ {"d":3000}, {"d":2500,"slots":[...]}, {"d":3000,"slots":[...]} ], "tdur":8500, ... }
```
- Scene 0 hamesha root `slots` hai (scenes[0] me sirf length `d`). Purana app / v772 Editor ise normal 1-scene template dikhata hai.
- Scenes 1.. ke apne `slots` (pehla = us scene ka full-canvas base). Max 5 scenes, scene length 1s..10s. `tdur` = scenes ka sum (card / preview ka duration).
- Single-scene template ka JSON pehle jaisa, `scenes` key nahi likhi jati.

## Create Template (`CreateTemplateActivity`)
- Layout tab me Layouts ke neeche **Multi-Scene** (title, (i) info, switch). ON karte hi 2nd scene ban jata hai.
- Scene strip: har scene ka mini preview + length, "+" se scene jodo (max 5). Scene par tap = select; Layouts grid us scene ka layout badalta hai.
- Chips 2s / 3s / 4s / 5s / 8s / 10s = selected scene ki length. "Scene hatao" (kam se kam 2 rehte hain), "Total X s".
- Frame, filter, animation, music, category sab scenes par ek jaise.

## Composer (`NewStatusActivity`)
- Template ke har scene ke boxes add hote hain; sirf current scene ke boxes dikhte hain (baaki INVISIBLE, layout me rehte hain).
- Neeche media strip multi-scene me **scene strip** ban jati hai: thumbnail + length, jis scene me khali box ho uspe purple "+". Tap = scene badlo.
- Gallery se ek saath kai photos chuno to sab scenes ke boxes order me bharte hain; scene poora bharne par agle khali scene par auto-switch.
- Post gate: "Fill N photo boxes" sab scenes ke boxes ginta hai.
- Post: `prepareAndUploadTemplateScenes` — har scene ka backdrop + still photo boxes ek bitmap me (720 wide), animated / video boxes compositor ko, phir **ek hi mp4**. Text / music / stickers pura video ke upar overlay.
- "Music Sync" ON ho aur viewer ne music rakha ho to saare scenes music ki length me scale hote hain.

## Compositor (`TemplateVideoCompositor`)
- Naya `composeScenes(List<Scene>, ...)` + `Slot.startMs`: scene ka background apne start time se, box apni window me (start..start+duration).
- Later scenes ke clips tab khulte hain jab scene shuru ho, khatam hone par decoder free (ek saath 5 decoder nahi).
- Purana `compose(...)` waisa hi (single scene).

## Chhote
- `TemplatePreviewActivity`: Photos / Videos tiles sab scenes ginte hain, Duration scenes ka sum.
- `version.properties` 144.

## Abhi nahi (jaan-boojhkar)
- Multi-scene me mp4 ki apni awaaz sirf tab jab pehla video box scene 1 me ho aur music sticker na ho; warna mp4 silent.
- Scene-wise alag filter / animation / frame nahi (sab scenes par global). Template Editor sirf scene 1 ke boxes badalta hai.
- Preview card / animated cover sirf scene 1 dikhata hai.
- Drafts tab abhi bhi khali (Create flow draft save nahi karta).

## Test (device par)
1. Create Template > Layout > Multi-Scene ON: strip me 2 scenes, "+" se 3rd. Scene tap -> layout grid us scene ka. Length chip badlo, Total update ho.
2. Switch OFF -> sirf Scene 1 ka layout, Next ke baad normal single-scene template.
3. Next -> composer: strip me 3 scenes. Gallery se 6+ photos chuno -> boxes bharen, scenes auto-switch ho.
4. Post -> video bane (scene 1 -> 2 -> 3, lengths wahi jo chuni). Animation (Effects me Zoom In) har scene me chale.
5. Dusre account se Use Template -> scene strip + khali boxes; sab bharo -> Post -> video me scenes order me.
6. Music Sync: song + sync ON -> video ki length song clip jitni.
7. Logcat tag `TplVideoComp` agar green frame / crash / slow ho.

**Build nahi chala** (Gradle yahan nahi): sirf bracket-balance, `javac` parse (syntax) aur XML check. Compile error aaye to bhej do.
