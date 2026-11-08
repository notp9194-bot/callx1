# v776 — Templates home bottom nav

Nav ab: **Templates | Saved | (+) | Analytics | Notifications** (Profile hata).
- **+** waisa hi (Create Template).
- **Analytics:** apne template ki Analytics screen (`TemplateAnalyticsActivity`).
- **Notifications:** apne template ke users ki list (`TemplateUsesActivity`, "Used This Template").
- Dono screens ek template ki hoti hain, isliye: 1 template ho to seedha khulti hai; kai ho to bottom sheet ("Kis template ka analytics?" / "Kis template ke users?") — preview, kitni der pehle, uses count. Koi template nahi to toast.
- Apne templates = tray me wo live statuses jinme templateJson ho aur jo dusre ke template se na bane hon (My Templates > Created jaisa).
- Files: NEW `OwnTemplatePicker.java`. MOD `TemplateBrowserActivity.java` (buildNav), `strings_template_browser.xml`, `version.properties` 147. Icon: existing `ic_tpl_analytics`.
- Note: Notifications tab ab app ki NotificationCenter nahi kholta, template-users list kholta hai (jaisa kaha).

Test: 2+ templates ke saath Analytics tap -> sheet -> template chuno -> graph screen. Notifications tap -> sheet -> users list. 1 template -> seedha. 0 -> toast.
Build nahi chala (Gradle yahan nahi): javac syntax parse.
