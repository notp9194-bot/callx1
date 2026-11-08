# v751 — Template: smart photo fit, auto-fill, Saved tab (base: v750)

## Status check (code me dekha)
- Item 1 Templates browser, 2 "kisne use kiya", 3 auto credit sticker → v749 me done + manifest/Status tab me wired.
- Item 5 box shapes/frames, 6 locked/placeholder text, 7 reveal animation, 8 save/share/link, 9 owner control → v750 me done, manifest + DeepLinkRouter me registered.
- Item 4 (smart photo fit + auto-fill) → **ye v751 me ab bana**. v750 ke 3 "NOT done" items bhi ab done.

## Done in v751
1. **Smart photo fit (face-aware crop)** — `TemplateSmartCrop` (naya). Viewer box me photo daalta hai → pehle center-crop dikhta hai, phir background me face detect hota hai aur crop window faces par shift ho jati hai (sir/chehra kat'te nahi). Face na mile to center-crop hi rehta hai. Viewer ne khud adjust (pinch/drag → Done) kar diya to auto crop dobara nahi hilta.
   - Framework `android.media.FaceDetector` use kiya (koi naya Gradle dependency nahi, offline). Ye sirf lagbhag seedhe (frontal) chehre pakadta hai; side/tilted chehra miss ho sakta hai.
2. **Auto-fill from recent photos** — template composer me pill "✨ Auto-fill N boxes (recent photos)" (jab tak koi box khali hai). Newest photos (screenshots skip, sirf kami par use) khali boxes me order se bharti hain, har photo par face-fit lagta hai. Photos permission nahi hai to ek baar maangta hai. Box par tap karke gallery se chunna pehle jaisa hi hai.
3. **Templates screen: Discover | Saved tabs + ♡ heart** — har card par heart (save/unsave). Saved tab = `savedTemplates/{me}` (last saved pehle, max 60).
4. **Reuse band hone par card hide** — Discover me un templates ke cards nahi dikhte jinka owner ne reuse off / template remove kiya (`templateControl`). Saved tab me dim + "Owner ne band kiya" dikhta hai taaki heart se hata sako.
5. **Slot zoom dialog me asli shape** — circle/heart/arch box me pan/zoom karte waqt shape ke bahar ka hissa dim dikhta hai (`TemplateBoxStyle.outsideMask`).

## Files
- NEW  feature-status/.../compose/TemplateSmartCrop.java
- MOD  NewStatusActivity (fillTemplateSlot, auto-fill pill, zoom dialog), TemplateBoxStyle (outsideMask), TemplateBrowserActivity (rewrite: tabs/hearts/hide)
- MOD  firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json → `savedTemplates/$uid` par `".indexOn": ["ts"]` (sirf index; **rules dobara publish karo** warna Saved list logcat warning ke saath chalegi, phir bhi chalegi)
- MOD  version.properties → VERSION_CODE=135

## Test (device par)
1. Template use karo → ek aisi photo daalo jisme chehra upar/kone me ho (portrait box) → chehra box me rehna chahiye. Pinch/drag karke Done → ab auto nahi hilna chahiye.
2. Pill "Auto-fill" tap → permission → saare khali boxes recent photos se bharein; screenshot na aaye.
3. Templates → ♡ dabao → "Saved" tab me aaye; dobara dabao → hat jaye.
4. Kisi doosre account se apni template ka "Allow reuse" off karo → us account ke Discover me card gayab; Saved me dim.
5. Circle/Heart box par photo tap → adjust dialog me shape ke bahar dim.

## NOT verified / baaki
- Gradle/network nahi tha → compile nahi hua, device par test nahi hua. Sirf bracket-balance check + code read hua. Build karke koi typo ho to bata dena.
- Face detect basic hai; agar side-face bhi chahiye to ML Kit Face Detection dependency baad me lag sakti hai (alag version).
- Poster-side photo layers (jab template *banate* ho) par face-fit nahi lagaya — sirf viewer ke boxes par.
