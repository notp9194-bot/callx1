# v770 — My Templates (Screen 6) + Followers/Following filter (Screen 7)

## My Templates (`MyTemplatesActivity`, naya)
- Templates home ka **My Templates** pill ab is naye screen par le jata hai. Tabs: **Created / Saved / Used**.
- **Created:** aapki live templates (status tray se) + aapke saved snapshots. Row par **analytics icon** (kisne use kiya list)
  aur **⋮** (owner sheet: kisne use kiya, reuse on/off, link share/copy, hatao).
- **Saved:** `savedTemplates/{me}`. ⋮: Use, link share, link copy, Saved se hatao.
- **Used:** naya `userTemplateUsed/{me}` index. Jab aap kisi template se status post karte ho tab likha jata hai
  (snapshot ke saath, to 24h ke baad bhi dikhta hai). ⋮: Use again, share, copy, list se hatao.
- Row tap -> Preview screen (v769). Neeche **Create New Template** button.
- Naya icon: `ic_tpl_analytics.xml` (bar-chart) — core/feature-status me iska koi equivalent nahi tha. Baaki sab existing drawables.

## Used This Template (`TemplateUsesActivity`)
- **All Users / Followers / Following** chips. `reelFollowers/{me}` aur `reelFollows/{me}` ek baar padhe jate hain
  (pehli baar filter chunne par). Filter sirf **load hue users** par lagta hai; list chhoti ho to 5 pages tak khud aur load karta hai.

## Data / rules
- `StatusTemplateUses.record(...)` ka naya overload (name + json + stickers snapshot); purana signature chalta hai.
  NewStatusActivity snapshot deta hai; scheduled-post path sirf name deta hai (preview nahi, par row bnti hai).
- **Rules (deploy karo, app se pehle):** `userTemplateUsed` node `firebase_template_rules.json` aur
  `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json` dono me add hua. Sirf owner apni list padh/likh sakta hai.
  Bina rules ke Used tab khali rahega (baaki sab chalega).

## Jaan-boojhkar nahi kiya
- **Drafts tab nahi hai.** Template draft ka matlab hai adhura bana template save karna, jo Editor (v772) ke bina nahi ho sakta.
  Tab Editor ke saath aayega. Abhi koi fake/khali tab nahi dala.
- **Used tab sirf v770 ke baad ki activity dikhata hai** (pehle ka koi record nahi tha).
- Analytics icon abhi "Used This Template" list kholta hai; asli Analytics screen v771 me.
- Browser ka purana MODE_MINE code abhi bhi hai par pill se ab use nahi hota.

## Test
1. Templates -> My Templates pill -> 3 tabs; Created me apna template, uses count aaye; analytics icon + ⋮ chalein.
2. Preview me bookmark -> back -> Saved tab me aaye. ⋮ -> Saved se hatao.
3. Kisi template se status post karo -> My Templates > Used me aaye (rules deploy ke baad).
4. Used This Template -> Followers / Following chips; All Users par wapas.
**Build nahi chala** (Gradle yahan nahi hai) — compile karke errors bhej do.
