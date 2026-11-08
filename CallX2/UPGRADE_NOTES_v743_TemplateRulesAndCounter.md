# v743 — Template: Firebase rules + app-side alignment

Base: v742. Rules file: `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json` (publish this whole file;
`firebase_template_rules.json` now holds just the template snippet for reference).

## Rules (additions only — nothing existing was changed)
- NEW `statusTemplateUses/{ownerUid}/{statusId}/{userUid}` ("N used"):
  read = signed in; a user writes only his own row; value = server time; template must be live (`templateJson` exists, not deleted);
  owner can't count himself; blocked users can't; a row can't be rewritten (1 per template+user); owner may wipe the whole counter.
- `status/$uid/$statusId`: size validators — `templateJson` ≤ 60000, `templateFromUid/Id` ≤ 128, `templateFromName` ≤ 120.
- `messages/$chatId/$msgId` (forwarded template): same validators + `templateStickersJson` ≤ 60000.

## App
- NEW `core/.../utils/StatusTemplateUses` — `record(originUid, originId)` (skips self/empty), `clear(owner, id)`, `capName()`.
- Composer: `recordTemplateUse()` uses the helper; `buildTemplateJson()` returns null if > 60000 chars; template name capped at 120.
- Scheduled status made from a template: use is counted when it actually goes live (`StatusRepository.publishScheduledStatus`) — earlier it was never counted.
- Delete status (`StatusRepository.deleteStatus`, `StatusSeenTracker.deleteStatus`) → counter removed.
- Repost / forward / `StatusItem.toMap`: `templateFromName` capped at 120 so the rules never reject the write.

## Deploy order
1. Publish the rules file. 2. Ship the app. (Old app + new rules is fine: all new rules are validators on fields the old app writes within limits.)

## Test
- Use a template → `statusTemplateUses/{owner}/{id}/{me}` appears; use again → rejected (count stays).
- Owner opens own template → "Your template", no self row.
- Schedule a template-made status → row appears at publish time.
- Delete the template → `statusTemplateUses/{owner}/{id}` gone.

## v744 — Template = collage of 2..4 photos
- `MAX_TEMPLATE_PHOTOS = 4` (base + max 3 boxes). 5+ photos par "Make template" pill dim + "Template: max 4 photos", tap par toast, template auto off.
- `buildTemplateJson()` bhi 1 photo ya 5+ par null deta hai (post par bhi guard).
- Purane (5+ box) templates viewer side par waise hi chalte rahenge.

## v745 — Template text editable (hint)
- Poster ka text template me normal editable text layer ban kar aata hai (pehle se editable: select → dobara tap → edit dialog; delete / naya text bhi ho sakta hai).
- Pehli photo bharne par ek baar hint toast: "Text badalna hai? Text par tap karo, phir dobara tap karke edit karo".

## v746 — Template text / music position lock
- Bug: "Use this template" par poster ka music sticker aur text apni jagah se hat jate the (music top-left ratio + card height badalne se; layout resize par bhi drift).
- Fix: `anchorTemplateSticker()` — restored text aur music (`cxRatio/cyRatio`, text ke liye `cx/cy`) poster ke CENTRE par pin hote hain
  aur sticker / canvas ke har re-layout par wahi centre dobara apply hota hai (card height, text edit, canvas resize).
- Viewer us sticker ko drag / pinch / rotate kare → `setOnTransformCommittedListener` anchor hata deta hai → uski apni position rehti hai.
  Na chhue to poster wali jagah hi rehti hai.

## v747 — Template boxes: har box ka apna colour
- Template composer me har khali box ka dotted outline + "+" + halka tint ek hi colour ka; agla box dusre colour ka
  (sky blue → orange → pink → green → violet → yellow, `TemplatePreviewDrawable.slotColor(i)`).
- Photo lagte hi tint (`clearColorFilter`) aur outline hat jata hai — photo apne asli rang me.
- Chip / pill ka mini preview bhi wahi colours use karta hai.

## v748 — Repost card: music sticker size bug
- Bug: repost card (normal ya template status) me music sticker original se bada dikhta tha.
- Root cause: `StatusViewerActivity.renderStickers` me repost card par music sticker ko `Math.max(scale, 1.1f)` se force-bump kiya jata tha.
- Fix: bump hata diya — ab poster ka apna scale x fitK (card width / poster canvas width) → card ke saath proportional shrink, baaki stickers jaisa.

## v749 — Templates browser, "who used", auto credit (rules me koi change nahi)
1. **Templates browser** — `TemplateBrowserActivity` (Status tab me naya "Templates" icon, Archive/Scheduled ke saath). Cache me jo LIVE
   template statuses already hain (contacts + apni) unhe grid me dikhata hai: mini layout preview, "@owner", "N photos • N used",
   most-used pehle (tie: newest). Original aur uski reposts ek hi card (original wins). Tap → normal template composer.
   Naya index / rules nahi → sirf wahi dikhta hai jo tum status viewer me pehle se khol sakte ho.
2. **Kisne use kiya** — owner apni template status par chip "Your template • N used" tap kare → `TemplateUsesActivity`
   (statusTemplateUses/{me}/{statusId}: naam + avatar + kab, newest first). Row tap → us user ki status viewer me (viewer apni privacy rules lagata hai).
3. **Auto credit sticker** — template use karte hi "✨ Template by @owner" mention sticker khud lag jata hai (bottom-centre, movable/removable,
   tap → owner ki profile; owner ko normal mention ping). Owner ke apne use par nahi. Core: mention sticker JSON me optional `prefix`.
