# v757 — Status feed fan-out (server + app)

## Idea
Server keeps `statusFeed/{viewer}/{owner}` = summary of that owner's live statuses the viewer may see
(privacy only/except/close-friends + blocks applied). App listens to ONE node (`statusFeed/{me}`) and
attaches `status/{owner}` listeners only for owners in it (was: one per contact, x3 places).

## Server (deploy from `server_deploy/`)
- `server_deploy/functions_index.js` -> Render: beside the server as `functions_index.js` (or `functions/index.js`). Cloud Functions deploy: same content as `functions/index.js`.
- `server_deploy/index.js` -> Render Express server.
- New: `syncStatusFeed` (callable, POST `/status/feed/sync`), `reconcileStatusFeed` (every 15 min), `onStatusWriteSyncFeed` (real Cloud Functions only).
- Render bridge fix: shim had no `.onDelete`, so loading functions with the audio triggers threw and ALL bridged actions/sweeps returned 503.
- Rules: `firebase_rules_v5_statusfeed.json` = deployed rules + `statusFeed`, `statusFeedMeta` (viewer read-only), `statusFeedOwners` (server only). Publish rules BEFORE the app.

## App
- `core/.../utils/StatusFeedHub.java` (new): feed listener, own-status watcher -> server sync, server call.
- `MainActivity`: badge + story-notification listeners follow the feed (per-contact fallback if feed unavailable).
- `StatusFeedHub` states: PENDING -> READY, or UNAVAILABLE after 5 s (old per-contact listeners).
- `StatusFragment`: incremental `status/{uid}` listeners for me + feed owners.
- Baseline profile rules added.

## Not covered / known
- Status tab still does not filter per-status privacy client-side (feed only decides which owners are listened to).
- Not compiled or run on device; server logic tested against an in-memory fake DB only.
