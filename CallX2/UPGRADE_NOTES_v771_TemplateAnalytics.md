# v771 — Template Analytics (Screen 8)

## Naya: `TemplateAnalyticsActivity` (owner only)
- My Templates > Created > **analytics icon** ab yahan kholta hai (pehle "Used This Template" list kholta tha).
- Sab data `templateStats/{me}/{statusId}` (v767) se, koi fake number nahi:
  - **Total Uses** (+ "+N today"), **This Week** (+ % vs last week / "New this week").
  - **Uses over time** smooth graph, 7 / 30 days toggle, point tap karke value dekho (peak default selected).
  - **Summary:** best day, average/day, today.
  - **Kisne use kiya** button -> Used This Template list.
- Din-wise buckets (UTC) sirf v767 ke baad ki uses ke hain; purani uses sirf Total me. Din-wise data na ho to note dikhta hai.
  Purane template (counter nahi) ka total who-used rows se aata hai.

## Jaan-boojhkar nahi kiya
- **Completion rate** aur **Top regions** nahi hain: app in dono ka koi data collect nahi karta. Fake dikhane ke bajay hata diye.
  Chahiye to tracking alag se jodni padegi (region ke liye privacy ka dhyan rakhna hoga).
- Koi naya drawable nahi (sirf ic_arrow_back). Rules me koi change nahi.

## Files
`TemplateAnalyticsActivity.java`, `strings_template_analytics.xml`, Manifest, MyTemplatesActivity (icon wiring), `version.properties` 141.

## Test
1. My Templates > Created > analytics icon -> cards + graph; 7/30 toggle; graph par tap.
2. Kisi dusre account se template use karwao -> Total, "+1 today", This Week badhe.
3. v767 se pehle ka template: din-wise note dikhe, total sahi.
**Build nahi chala** (Gradle yahan nahi hai) — compile karke errors bhej do.
