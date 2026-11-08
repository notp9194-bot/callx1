# v774 — My Templates > Drafts

- **Create Template:** kuch badalke back dabao -> "Draft me save karo?" (Save draft / Discard / Edit karte raho). Kuch nahi badla to seedha band.
- **Drafts tab** (Created | Saved | Drafts | Used): card me layout preview, "Draft", "N scene(s) · kitni der pehle". Tap ya ⋮ > Draft edit karo -> Create Template wahi state (scenes, frame, filter, animation, music, category) ke saath khulta hai. ⋮ > Draft delete karo.
- Draft dobara save karne par wahi draft update hota hai (nayi copy nahi). Max 20 drafts (purane hatte hain).
- **Sirf is device par** (SharedPreferences `template_drafts`, account-wise). Posted template nahi hai, isliye cloud / rules me kuch nahi badla.
- Next dabane par draft delete nahi hota; Drafts tab se hata sakte ho.
- Files: NEW `TemplateDrafts.java`. MOD `CreateTemplateActivity.java` (EXTRA_DRAFT_ID, save / restore / back dialog), `MyTemplatesActivity.java` (Drafts tab, onResume reload), `strings_my_templates.xml`, `strings_template_create.xml`, `version.properties` 145.

## Test
1. Create Template > Multi-Scene ON, layout badlo > back > Save draft. My Templates > Drafts me card dikhe.
2. Card tap -> sab choices wapas. Kuch badlo > back > Save draft -> list me wahi card (naya nahi), upar aa jaye.
3. ⋮ > Draft delete -> card gayab. Khali tab me hint text.
4. Bina badlav back -> koi dialog nahi.

Build nahi chala (Gradle yahan nahi): bracket-balance, `javac` syntax parse, XML check.
