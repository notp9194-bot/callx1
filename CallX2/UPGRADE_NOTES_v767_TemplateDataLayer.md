# v767 — Template v2, step 1: data layer (no new screens yet)

Base: v766. Ye step sirf data/counter/model hai — Preview, Editor, Analytics screens next steps me isi par bante hain.

## 1. `useCount` counter (`templateStats`)
- Naya node `templateStats/{ownerUid}/{statusId}` = `{ uses: N, days: { yyyyMMdd(UTC): n } }`.
- `StatusTemplateUses.record()` ab pehle `statusTemplateUses` row likhta hai; row accept hone par hi ek transaction se `uses` +1 aur aaj ka `days` bucket +1.
- `StatusTemplateUses.fetchCount()` = sirf ek number read. Purane templates (counter node nahi) par purana tareeka (rows count) fallback.
- `StatusTemplateUses.fetchStats()` = total + per-day buckets (Analytics screen ke liye).
- Readers badle: Templates home card (`fetchMeta`), viewer chip "N used", Used-This-Template screen (ab "40+" ki jagah exact total jab counter ho).
- Delete / expiry par `templateStats` bhi hatta hai (`StatusTemplateUses.clear`, `functions/index.js cleanupExpiredStatusReplies`).

## 2. Rules (publish karo — app se pehle)
- `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json` me naya `templateStats` block (same snippet `firebase_template_rules.json` me).
- Write allowed: owner sirf delete; baaki user sirf +1 aur tabhi jab uski apni `statusTemplateUses` row < 5 min purani ho. Fields type-validated, unknown field reject.
- Limit (jaan ke): ek user 5 min window me counter ko kuch baar +1 kar sakta hai (row ek hi hoti hai). Best-effort counter hai, paisa/ranking security nahi.

## 3. `TemplateSpec` (naya, `compose/`)
templateJson me optional keys (v:1 hi rehta hai, purane templates/apps par koi asar nahi):
- slot: `k` ("img" default | "video"), `d` slot duration ms (1000..15000), `flt` (natural/warm/cool/bw/vivid/fade), `an` (none/zoom_in/zoom_out/pan_l/pan_r/fade)
- root: `tdur` total ms (max 60000), `msync` 1 = Music Sync
- `TemplateSpec.parse(json)` (kabhi throw nahi karta), `Slot.putJson`, `putMeta`, `displayMs()`, `formatDuration()` ("0:15").
- Abhi koi UI ye keys likhta nahi — Editor (screen 4) aur video slots (step 2) inhe use karenge.

## Files
NEW compose/TemplateSpec.java · MOD core StatusTemplateUses, TemplateBrowserActivity, TemplateUsesActivity, StatusViewerActivity, functions/index.js, 2 rules JSON, version.properties (VERSION_CODE=137)

## Test
1. Dusre account se template use karke post → `templateStats/{owner}/{id}/uses` = 1, `days/{aaj}` = 1; owner ke Templates card par "1" uses.
2. Dobara use try → row rejected, counter nahi badhta.
3. Purana template (counter node nahi) → card par purani tarah count.
4. Template delete → `templateStats` node gayab.
- Not compiled / run on device (Gradle yahan nahi tha) — build karke typo ho to batao.
