# v742 — Template: carry, base fit/blur, preview (items 10-12)

Base: v741 (TemplateAttribution). Items 1-9 were already done; this build finishes 10, 11, 12.

## 10. Template repost / forward mein aage jata hai
- `StatusReshareHelper.buildReshareStatusIntent` ab `templateJson` + ORIGINAL template ka `fromUid/fromId/fromName` extras bhejta hai
  (chain repost A→B→C mein bhi origin A hi rehta hai). Template-origin se bani status ki repost sirf "Template by @name" attribution carry karti hai.
- `StoryReshareActivity.doShareToStory` repost row me `templateJson`, `templateFromUid/Id/Name` likhta hai.
- `StatusViewerActivity.renderTemplateChip`: repost par bhi "Use this template" chip; "N used" count, launch, "Your template" check
  sab ORIGINAL template (`statusTemplateUses/{originUid}/{originId}`) ke hisaab se. Repost card ke andar chip card ke bottom par baithta hai.
- `StatusForwardBottomSheet`: chat message me `templateJson`, `templateStickersJson`, `templateFromUid/Id/Name` jodte hain
  (helper `StatusReshareHelper.templateOrigin`). Firebase message rules me extra fields allowed hain — rules change nahi.

## 11. Base photo ka fit/blur
- `buildTemplateJson`: base slot ab `ar` (poster photo ka aspect) save karta hai; agar poster ki photo canvas ko poora cover nahi karti
  (fit / zoom-out / rotate) to `fit:true, cx, cy, w, h, rot` bhi.
- Composer (template mode): fit wale base ka box poster ki photo wali jagah dikhta hai (dashed). Viewer ki photo CROP nahi hoti —
  poori photo us region ke andar fit hoti hai, backdrop viewer ki photo ka blur ban jata hai (`applyFitSlot`).
  Fit box par tap = photo change (pan/zoom ki zaroorat nahi). Cover wale / purane templates pehle jaise full-cover.

## 12. Preview thumbnail + cleanup
- Naya `TemplatePreviewDrawable`: templateJson se seedha 9:16 mini layout draw karta hai (no network, no upload, no extra read).
- Viewer chip par preview; composer ke "Make template" pill par bhi preview jab template ON ho.
- Template OFF karne par / photo layers hat jaane par pill ka preview drawable poora clear (`refreshMetaPills`).

## Files
- NEW  feature-status/.../compose/TemplatePreviewDrawable.java
- MOD  NewStatusActivity, StatusViewerActivity, StatusReshareHelper, StoryReshareActivity, StatusForwardBottomSheet

## Test (device par)
1. Template banao (fit wali landscape base photo + 2 photo boxes) → doosre account se chip → base box poster jaisi jagah, photo crop nahi, blur backdrop viewer ki photo ka.
2. Us template ko repost karo → repost par "Use this template" chip + preview; "N used" original wala; use karke post → original ka count badhe.
3. Template ko chat me forward karo → message node me templateJson + templateFromUid/Id.
4. Make template ON → pill par preview; OFF → preview gayab.
