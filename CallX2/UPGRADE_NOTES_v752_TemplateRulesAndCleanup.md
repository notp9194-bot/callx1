# v752 — Template: Firebase rules + app/server alignment (base: v751)

## Mila kya (tumhari upload ki hui rules file check karke)
Tumhari live rules file v749 jaisi thi. v750 ke ye nodes usme **the hi nahi**, isliye v750 ke features device par "permission denied" se fail hote:
- `savedTemplates` (template save / ♡ / Saved tab) — fail
- `templateControl` (owner ka "Allow reuse" switch, template hatao) — fail
- `templateReports` (report) — fail
- `status/.../templateLayoutJson` (box-by-box reveal ka layout) — validator missing
- `statusTemplateUses`: "reuse band hone par naya use block" clause missing

## Rules (file: `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json` — tumhari file par additions, baaki kuch nahi badla)
- ADD `savedTemplates/$uid`: sirf apna read/write, `indexOn ts`, har field ka type/size validator, unknown field reject.
- ADD `templateControl/$owner/$statusId`: read = signed-in, write = sirf owner; `off`/`removed` boolean, `ts` number, baaki reject.
- ADD `templateReports/$owner/$statusId/$reporter`: read = band, write = sirf apna, **owner khud ko report nahi kar sakta**, ek user = ek report, reason ≤ 40, `ts` number.
- ADD `templateLayoutJson` validator (≤ 8000).
- CHANGE `statusTemplateUses` validate: owner ne reuse off / template remove kiya ho to naya use reject.
- **Publish karo** (poori file) — App se pehle ya saath me.

## App
- `TemplateActions.report`: toast ab asli result ke hisaab se. Pehle "Report bhej diya" turant dikhta tha chahe rules reject kar dein (2nd report par). Ab: success / "pehle hi report kar chuke ho" / "dobara try karo".

## Server (functions/index.js — `cleanupExpiredStatusReplies`)
- Expired status delete hone par uske `statusTemplateUses`, `templateControl`, `templateReports` nodes bhi hatt jate hain (pehle ye hamesha ke liye bache rehte the). `savedTemplates` ko nahi chhua — saved copy 24h ke baad bhi chalni chahiye.
- Deploy: `firebase deploy --only functions:cleanupExpiredStatusReplies`

## Test
1. Rules publish ke baad: template ♡ save → `savedTemplates/{me}` me entry; Saved tab me dikhe.
2. Owner "Allow reuse" off → doosre account se use try → "reuse band" toast; `statusTemplateUses` me row na bane.
3. Report do baar → pehli par "shukriya", doosri par "pehle hi report kar chuke ho".
4. Rules simulator: owner khud ko report kare → denied.

## Dhyan (abhi nahi kiya)
- "N used" abhi poora `statusTemplateUses/{owner}/{id}` download karke count karta hai. Bahut popular template (hazaaron uses) par card heavy ho sakta hai — tab ek `useCount` counter (transaction) alag version me lagana padega.
- `statusTemplateUses` ka read har signed-in user ko hai (count dikhane ke liye), yani koi bhi us list ke uids dekh sakta hai.
- Gradle nahi tha → app compile nahi hua. Rules JSON validate ho gayi, functions/index.js syntax-check pass.
