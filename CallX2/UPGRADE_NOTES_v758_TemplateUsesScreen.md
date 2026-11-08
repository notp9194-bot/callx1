# v758 — "Used This Template" screen (screenshot design) + core avatar + shared story ring

## Files
- `feature-status/.../compose/TemplateUsesActivity.java` (rewritten, XML-based)
- new `res/layout/layout_template_uses_skeleton.xml`; rules: `firebase_template_rules.json`, `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json`
- new `res/layout/activity_template_uses.xml`, `res/layout/item_template_use.xml`
- new `res/values/strings_template_uses.xml`, `res/values/template_uses_dimens.xml`

## Screen
- Top bar: back (`ic_back`), title "Used This Template", more (`ic_more_vert`) -> owner sheet (`TemplateActions.showOwnerSheet`).
- Summary card: thumbnail + `ic_play`, template name (caption/text of the status), "Story Template" chip (`ic_sbar_copy`),
  "N people used this template" (`ic_pill_people`), Save (`ic_bookmark` / `ic_bookmark_filled`, `TemplateActions.save/unsave/isSaved`;
  shown only while the status still carries its templateJson).
- "Used By" + "N People" (12.8K style, "200+" at the 200-row read cap).
- Row: avatar + story ring + green online dot (`bg_circle_green`, users/{uid}/online) , name, @username, time, View button.
- Reused drawables only: ic_back, ic_more_vert, ic_play, ic_sbar_copy, ic_pill_people, ic_bookmark(_filled), bg_circle_green,
  rounded_input_bg, card_rounded, circle_status_unseen/seen_thin via StoryRingApplier. No new drawables.

## Optimizations (same as the Seen-by sheet)
- Paging 40 + older pages on scroll (endAt cursor), footer spinner, tap-to-retry; live deltas via child listener.
- Lazy profile fetch for bound rows only (6 in flight, latest-bound first); static LruCache(600) + disk persistence (800), 6h TTL, stale shown instantly; Room chat seeds; profiles also pre-warm the Seen-by sheet cache.
- Online dot: tiny users/{uid}/online read for bound rows only (4 in flight), never persisted.
- bg thread builds+sorts rows; debounced refresh; DiffUtil ListAdapter + time-only payload and 60s ticker; skeleton rows until first page; first-page error state.
- DEPLOY: `.indexOn [".value"]` on statusTemplateUses/$owner/$statusId (firebase_template_rules.json + firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json) — publish rules before the app (without it paging is client-side).
- Count shows "40+" while older pages exist (no server counter for template uses).

## Avatar / ring
- `AvatarBinderCore.bind` (tier TINY, StatusAvatarL2Cache L2+L3, versioned URL), cancel on recycle, velocity prefetch, initials fallback.
- `StoryRingApplier.applyWithClick`: avatar 30dp, ring stroke 1.5dp, gap 2dp -> ring 37dp (`@dimen/tpl_use_avatar` / `tpl_use_ring`).

## Notes
- Not compiled / run on device.
- Screenshot avatar is bigger than 30dp; change `tpl_use_avatar` + `tpl_use_ring` (avatar + 7dp) in template_uses_dimens.xml if wanted.
