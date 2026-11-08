# v788 — Template: owner edits reach saved snapshots (21), server-side Discover (22), strings

NOT built / not device-tested. Needs: `firebase deploy --only functions,database` (rules) BEFORE the app build is useful for 21/22.

## 21 — Owner's edit reaches other users' saved snapshots
- New node `templateLatest/{owner}/{statusId}` = newest `templateJson` (+ stickers). Not swept with the 24h status.
- Written by (a) Cloud Function `onTemplateStatusWritten` (every status write / edit) and (b) Editor save (`writeLatest`, owner only).
- Browser `fetchMeta` (once per saved card): `TemplateActions.fetchLatest` (2 min cache). If it differs, the row is re-parsed and
  the user's OWN `savedTemplates` snapshot is updated (only if it still exists). Bad newest JSON → old version kept.

## 22 — Server-side Discover
- New node `templateFeed/{owner_statusId}` (Admin-SDK write only; clients read). Public ("everyone") ORIGINAL templates only.
- Functions: `onTemplateStatusWritten` (upsert / remove when status goes private), `onTemplateUsesChanged` (uses → score),
  `onTemplateControlChanged` (owner off/removed → `hidden`), `pruneTemplateFeed` (daily; 45 days old with 0 uses).
- Browser `loadFeed()`: trending (`score`, 60) + newest (`ts`, 40), merged into Discover; a cached status row wins over a feed row.
  Feed rows survive `loadDiscover` reloads (`fromFeed`).
- Existing templates only enter the feed when their status is next written. Optional one-off backfill: touch `templateJson`.

## Rules
`templateLatest` (read auth, write owner) and `templateFeed` (read auth, write false, indexOn score/ts/cat) added to
`firebase_template_rules.json`, `firebase_security_rules_UPDATED_audio_v4_statusviewers_template.json`, `firebase_rules_v5_statusfeed.json`.

## Strings
- `strings_new_status_toasts.xml`: all 45 literal `toast("…")` in NewStatusActivity + sticker-added + mention-not-found.
- `strings_template_polish.xml`: remove-dialog title, report reason labels (DB values unchanged), LinkActivity uses `tpl_act_unavailable`.
