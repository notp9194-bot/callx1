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
