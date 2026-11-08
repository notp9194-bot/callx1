# v750 — Template advanced polish (base: v749)

## Done
- **Box shapes + frames**: Square / Rounded / Circle / Heart / Arch, border, shadow. Poster: "Make template" pill -> options sheet (per box + "all boxes"). Stored per slot (`shape`,`bd`,`sd`); old templates unchanged. New: `TemplateBoxStyle`.
- **Locked text / placeholder text**: sheet "Text": Editable / Locked / Placeholder (`tm` in templateJson). Locked = no move/edit/delete; Placeholder = dimmed, Post disabled until viewer writes own text.
- **Reveal animation**: viewer opens a template photo with closed dotted boxes and lifts them one by one (`TemplateRevealCover`, `StatusItem.templateLayoutJson` saved on made-from-template statuses).
- **Save / share / link**: viewer chip "..." -> save (snapshot in `savedTemplates/{me}`), send in chat, share/copy link, report. Link `https://callx-server.onrender.com/template/{uid}/{id}` and `callx://template/{uid}/{id}` -> `TemplateLinkActivity` (falls back to saved copy; close-friends/only/except statuses never opened by others).
- **Owner control**: tap own chip (or "...") -> who used, Allow reuse switch, link, remove template (`templateControl`).
- Rules added to `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json`: templateControl, savedTemplates, templateReports, templateLayoutJson validator, uses blocked when reuse off. **Publish rules before shipping.**

## NOT done yet (left for later)
- Templates screen: "Saved" tab + heart on cards (saved templates are stored, but only reachable via the chip menu / link for now).
- Templates screen does not yet hide cards whose owner turned reuse off (tapping them shows the block message instead).
- Slot zoom dialog shows a rectangle (final photo still gets the shape).
- Not compiled here (no Gradle/network) — build once and fix any typo.
