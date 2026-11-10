# v791 — Remix & Collab: closing the v790 gaps

## Deploy order
1. `firebase_rules_FINAL_v791.json` → Realtime Database rules (replaces v790). Adds the `remixOf` index on `/reels`.
2. `functions/index.js` → `firebase deploy --only functions` (new: `onRemixApprovalRevoked`; everything else unchanged).
3. Build the app. `server_deploy/index.js` (Render) is UNCHANGED — no redeploy needed.

## What changed
| Gap (v790) | Fix |
|---|---|
| Save Draft lost remix/collab info | `ReelDraft` now stores `isRemix`, `remixMode`, `remixOriginal*`, `remixLayoutMode`. `ReelEditorActivity.saveDraftAndFinish` fills them; `ReelDraftsActivity.onDraftClick` hands them back to the editor. On resume the creator's permission is re-checked (`RemixAccessHelper`) — if it was revoked / turned off meanwhile, the draft is not opened as a remix and the user gets a clear message. |
| Credit strip only on Reels player | `tv_remix_credit` added to `item_home_feed_post.xml` (Home feed card) and `item_post_feed_photo.xml` (Posts feed); bound by `RemixCreditBinder` in `HomeFragment` and `PostsFeedActivity`. Binder now caches the "blocked?" lookup for 60 s so scrolling does not cause one DB read per bind. |
| Revoke did not touch already-published remixes | New Cloud Function `onRemixApprovalRevoked` (trigger: `remixApprovals/{reelId}/{uid}` deleted). Remixes by that user → `remixBlocks` (credit reads "Original removed by the creator", hidden from the original's Remixes list). Collabs by that user → original creator removed as co-creator (same result as `CollabControl.remove`). The user's own posts are never deleted. Revoke dialog text updated. |
| Followers-only only checked in the app | `reelRemixes` create rule now also requires `reelFollows/{me}/{owner} === true` when level is `followers` (owner exempt). |
| Collab accept untested | Traced the whole accept write against the rules — see "Bug found" below. Fixed in rules; notification quick-accept now writes the same fields as the accept screen. |

## Bug found while tracing "invite → accept" (rules, not app code)
`collabPostInvites` / `collabPostInvitesSent` rules in v790 (and older) used the wrong wildcard meaning:
the 2nd key was treated as a *uid* but the app stores the *inviteId* there. Result:
- initiator could not create `collabPostInvites/{invitee}/{inviteId}`;
- invitee's accept (one atomic multi-path update) included `collabPostInvitesSent/{initiator}/{inviteId}/status` → denied → **the whole accept failed** ("Failed. Please try again");
- the invitee's inbox list read was also not covered.
Now keyed on the record's own `initiatorUid` / `collaboratorUid` fields.

## Security hardening (found in the same pass)
- `reels/{id}/collabMap/{uid}`: a user could add *himself* as accepted collaborator on anyone's reel (then write `isCollabPost`, `collabUid`… on it), and a reel owner could mark anyone "accepted" without their consent. Now:
  - self-write only on an entry that already exists (i.e. was invited) and not after being removed;
  - owner may write pending / declined / removed freely, but "accepted" for someone else only for the Remix-Collab case: reel has `remixOf`, the publisher holds a `collab` approval on it, the entry is the original's real owner, and the original's Remix level is not `off`.

## Tested here
`firebase_rules_FINAL_v791.json` was run through an offline evaluator of the changed rule expressions: 33 scenarios (invite, accept, decline, leave, re-accept after removal, forged accepted, collab publish with/without approval, level off, followers/approval/everyone/off remix, inbox reads) — 33/33 pass. The same scenarios on v790 fail for the cases listed above.
Java: syntax-parsed with javac (no Android SDK here, so NOT built). XML layouts well-formed. `functions/index.js`: `node --check` OK.

## Still to test on a device (needs the app + deployed rules/functions)
1. Collab invite → accept (screen) AND accept from the notification button → both users see it in Collabs.
2. Save a remix draft → reopen from Drafts → post → credit strip + remix count appear.
3. Revoke an approval after a remix was published → remix credit changes to "Original removed…" (Cloud Function logs: `onRemixApprovalRevoked`).
4. Followers-only reel: a non-follower is blocked by the app AND by the rules (try forcing it).
5. Existing remix / collab data written before v791 is untouched; old reels without `remix_settings` still behave as "everyone".

## Not done / limits
- Revoke does not delete anything the requester posted (by design — those are their posts).
- `reelFollows` is read with `=== true`; if some old follow rows store another truthy value, followers-only would deny them in rules.
- No push notification when a Remix-Collab is published to the co-creator (in-app only), same as v790.
- `version.properties` (VERSION_CODE=148) not bumped.

## Hotfix (same v791): stuck on "Compositing…" after recording (Remix AND Collab)
Root cause: `ReelRemixVideoCompositor` (API 29+ path) built an encoder with an input Surface but never decoded/rendered any frame
into it and never signalled end-of-stream → its `while(true)` encode loop never ended (progress stayed ~0%). The user's recording
was never even opened. (The API<29 path only copied the original video and dropped the user's video.)
Fix:
- `ReelRemixVideoCompositor` now delegates to `DuetVideoCompositor` (the decode → OpenGL → encode pipeline Duet already uses). Same public API.
- `ReelRemixActivity.startCompositing`: single-outcome guard, 3-minute timeout, "mute original" now also mutes it in the final mix,
  and on failure a dialog: Retry / Use my video only / Record again (no more silent hang or silent raw fallback).
- Layout mapping: side_by_side → side by side; react_cam → original full + round camera bubble (top-right);
  green_screen / overlay → camera full + original as a small corner picture (nearest available look — true green-screen/alpha overlay is not implemented).
- Side-by-side order follows the Duet compositor (camera left, original right).
NOT verified on a device — please test: Remix record → merge → editor, Collab record → merge → editor, and each layout.
