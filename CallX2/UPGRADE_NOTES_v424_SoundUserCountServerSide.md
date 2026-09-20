# v424 — Sound "distinct users" count, maintained server-side

## Problem
* `sounds/{id}/reel_count` was bumped by a **client** transaction at upload /
  collab-accept time and never decremented on delete → drift.
* There was **no distinct-user count**, so Sound Detail's
  "Used by A, B and N others" could only estimate N from the reels loaded so far.
* `sounds/$key` had `".write": "auth != null"` → any signed-in user could write
  any sound's counters.

## What changed
### Cloud Functions (`functions/index.js`)
`onSoundReelOwnerWrite` — trigger on `sounds/{soundId}/reels/{reelId}/ownerUid`
(the leaf, so viewer `viewsCount` transactions never count). Sole writer of
`reel_count`, `user_count`, `is_trending` (>= 5 reels). Idempotent (per-owner set
of reelIds in `soundUsers/{soundId}/{ownerUid}/{reelId}`), `user_count` only moves on an
owner's 0<->1 transition, decrements never create/resurrect a node or go < 0.

### Render server (`index.js`)
`POST /admin/backfill-sound-counts` (x-admin-key): rebuilds `soundUsers` +
`user_count` (+ `reel_count` with `fix_reel_count=1`) from real reels entries.
Paginated: repeat with `after=<next>` until `done:true`. Also works as a repair tool.

### Rules
* `sounds/$key`: `reel_count` (init-to-0 only, keeps old remix builds working),
  `user_count`, `is_trending` → not client-writable. Everything else unchanged
  (`$other` keeps `auth != null`).
* New `soundUsers`: `.read/.write: false` (Admin SDK only). Kept OUT of
  `sounds/{id}` so whole-node reads (SoundDetailCache, search, trending) don't
  download it.

### App
* Removed the client `reel_count` transactions (`ReelUploadActivity`,
  `CollabRepostAcceptActivity`) and the remix `reel_count: 0` seed.
* `SoundDetailCache` reads `user_count` → `SoundDetailViewModel.soundUserCount` →
  `SoundDetailFragment`: "and N others" = `user_count − shown names`, exact, no "N+".
  If `user_count` is missing/0 (not backfilled) it falls back to the old
  loaded-so-far estimate.

## Deploy order (matters)
1. `firebase deploy --only functions` (trigger) and redeploy the Render server (backfill endpoint).
2. Publish the new **rules** right after step 1. Until then old app builds still
   bump `reel_count` themselves, so a reel added in that window is counted twice —
   step 3 repairs it.
3. Run the backfill until `done:true`, with `fix_reel_count=1` the first time:
   `curl -X POST "$SERVER/admin/backfill-sound-counts" -H "x-admin-key: $KEY" -H "content-type: application/json" -d '{"limit":25,"fix_reel_count":1}'`
   then repeat with `"after":"<next>"`.
4. Ship the app build.

Old builds keep working after step 2: their counter/`is_trending` writes are
rejected silently and the function does the counting instead.
