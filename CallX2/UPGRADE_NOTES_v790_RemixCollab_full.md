# v790 — Remix & Collab upgrade (steps 2–8 on top of step 1)

## Deploy order
1. `firebase_rules_FINAL_v790.json` → Realtime Database rules (replaces v789 file).
2. `functions/index.js` → `firebase deploy --only functions` (new: onRemixRequestCreated, onRemixRequestDecided; expireRemixRequests unchanged).
3. Build the app.

## Flow (design screens → code)
| # | Screen | Code |
|---|---|---|
| 1 | Entry | `ReelMoreBottomSheet`: "Remix This Post" (NEW) + "Collab with Friend", gated by creator level (off → hidden, followers → locked) |
| 2 | Choose Mode | `RemixCollabModeActivity` |
| 3 | Select Post | `RemixSelectPostActivity` (For You / Following / Trending, search, entry post pre-selected) |
| 4 | Request Permission | `RemixRequestActivity` |
| 5 | Request Sent / Status | `RemixRequestStatusActivity` (pending 48h countdown, approved, declined, expired, cancelled) |
| 6 | Edit | `ReelRemixActivity` (tap layout label = Layout switcher; mute original) → existing `ReelEditorActivity` (music, effects, text, stickers, trim, drafts) |
| 7 | Share | `ReelUploadActivity`: credit box + "Who can Remix & Collab" (Everyone / Followers / Approval / Off) |
| 8 | Profile Collabs | `UserReelsActivity` row "Collabs (N) See All" → `UserCollabsActivity` |
| – | Creator inbox | `RemixRequestsInboxActivity` (Received: approve/reject/revoke · Sent: status). Owner 3-dot menu: "Remix & Collab Requests" |

Routing lives in `RemixCollabFlow` + `RemixAccessHelper` (one place decides next screen).
Permission level: `reels/{id}/remix_settings/remix` = everyone (default, old reels unchanged) | followers | approval | off.
Collab always needs the original creator's approval.

## Important bug found and fixed
`ReelRemixActivity` passed `is_remix` + `remix_*` extras to the editor, but nothing forwarded them to the upload screen and
`publishRemixToFirebase()` was never called → remixes were never recorded, credited or counted. Now:
editor forwards extras → upload saves `isRemix/remixOf/...` on the reel → `RemixPublishHelper` writes `reelRemixes`, `remixCount`,
`userRemixes` + notification (remix) or makes the original creator an accepted co-creator (collab).

## Credit & control (recommendations 1–3)
- Credit: "Remix with @owner ❤" strip on the reel player (`RemixCreditBinder`), taps through to original.
- Revoke permission: inbox → Approved → Revoke (deletes `remixApprovals`; rules then block new publishes).
- Remove a remix: long-press in "View Remixes" (owner) → `remixBlocks`; credit then reads "Original removed by the creator".
- Remove collaborator / leave: long-press a row in the Collaborators sheet (`CollabControl`).

## Rules added (v790)
remixApprovals, remixBlocks, userCollabPosts, reelRemixes create-gate (off / approval), remixCount, collabMap self-write,
isCollabPost & co-author mirror fields for accepted collaborators, reelsByUser/userReels owner write for an accepted/removed co-author.

## NOT verified / known limits
- Not built or run on a device (no Android SDK here). Java was only parse-checked; please build and test.
- Save Draft: the editor's existing drafts don't store remix/collab metadata, so a resumed draft posts as a normal reel.
- Credit strip is on the Reels player only (not yet on the Home-feed post card / Posts feed).
- Collab feed fan-out: the co-creator gets the post on his profile/Collabs and reelsByUser index; no separate push on publish.
- Revoke does not delete remixes already published (use "remove remix").
- Followers-only level is checked in the app (not in rules).
- Existing collab accept used to write collaborator-owned fields under `reels/*`, which the old rules did not allow; v790 rules now permit it — worth testing invite → accept once.
