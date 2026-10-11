# v535 — Deleted/expired statuses lingering

- StatusViewerActivity: `pruneDead()` (runs on next/previous) + live listener on `status/{uid}` — deleted/expired items drop out of `items` and the top segment bars rebuild immediately.
- StatusFragment: listener parses into temp holders before clearing maps; per-child try/catch.
- StatusFragment.saveToRoom + StatusDao (`getAllPublishedIds`, `deleteByIds`): Room rows missing from Firebase snapshot are deleted, so cold start can't show deleted statuses.
- Status viewer header: 3-dot (`ic_menu_more`) replaced with the reels two-line more icon (`ic_status_more_lines`, copy of `ic_reel_more_lines`).
- Reply bubble avatars: 20dp, white border removed.
- Reply bubble: entry fade+rise, new-comment text cross-fade + avatar pop, exit fade+drop; signature dedupe skips redundant rebind/animation; GPU layer only while animating.
- Reply bubble: rounded pill bg (bg_status_reply_pill), hides with hold-to-pause chrome, auto-hide timer pauses/resumes with progress.
- Reply bubble: bold-name/dim-comment spans (name capped 14 chars), '+' badge when >3 comments (query limitToLast(4)), contentDescription, 48dp tap area via TouchDelegate.
- Reply bubble: after 4s it collapses to the avatar stack (+ badge) instead of vanishing; tap still opens comments, new comment expands it.
- Status photo crop: crop JPEG q93->98; stale crop/bake temps (>24h) purged on NewStatusActivity open. (Bake already skipped when crop is the only edit.)

## v543 — Status @mention (Instagram-style)
- functions/index.js: `onStatusCreatedMentions` (text/caption/mention-sticker -> usernames/{name} -> uid; writes status/.../mentionNames/{username}=uid, statusMentions/{uid}/{owner}_{id}, statusNotifications row, data-only FCM `status_mention`; skips self/unknown/blocked) + `cleanupExpiredStatusMentions` (6h).
- CallxMessagingService: `status_mention` push -> notification that opens the viewer on the exact status.
- StatusViewerActivity: "Mentioned you · Add to your story ✕" chip on statuses that mention me; reshare always allowed for the mentioned user; ✕ removes my mention (mentionNames entry + statusMentions row).
- NewStatusActivity: no longer writes the wrong name->"@name" mentionNames (server owns it).
- Rules: `statusMentions/{uid}` (read/delete by that user only; function writes via admin) + `status/.../mentionNames/$username` removable by the mentioned user.
- Deploy: `firebase deploy --only functions:onStatusCreatedMentions,functions:cleanupExpiredStatusMentions` + publish rules.

## v543b — Mention polish
- core `MentionUserSearch` + `MentionUi`: search picker for the mention sticker (contacts when empty, `usernames` prefix search, avatars) replaces the free-text box; `@name` autocomplete popup under status text + caption.
- NewStatusActivity: after posting, unknown @names get a "not found — not mentioned" toast (blocks are never revealed).
- StatusViewerActivity: tapping an @username in text status / caption opens that profile; "Mentions (N)" list in owner + viewer menus.
- Function: idempotent (retry-safe, deterministic notification key), target privacy `users/{uid}/statusMentionPrivacy` = everyone|followers|none (no settings UI yet), paged cleanup of expired statusMentions.
- Push: story thumbnail as big picture + grouping.
- Rules: `usernames` root readable (needed for prefix search), `statusMentionPrivacy` validated.

## v545 — Reshared story chip
- Reshared STATUS now shows a compact "↻ @owner" chip (`ll_reshare_chip`); reel/post/channel reshares keep the big card.
- Tap → `openOriginalStory()`: checks the original still exists / not deleted / not expired, I haven't blocked the owner, and its audience (only / except / close-friends lists) includes me → opens that exact story (`EXTRA_TARGET_STATUS_ID`). Otherwise toast + owner's profile.
- Chip fades out with hold-to-pause chrome.
- Rules: type/length validation for `resharedFrom*`, `privacy`, `isCloseFriends`.

## v546 — Reshared story polish
- Reshare stores `resharedFromOwnerUsername` (resolved at share time); chip shows "@username" (display name without "@" for old reshares) + avatar, fades/rises in.
- Chip tap: instant cache lookup (else status + block check read in parallel), "working" feedback, single-status open (`EXTRA_SINGLE_STATUS_ONLY`) so Back returns straight to the reshare; legacy reshares (no id) → toast + profile.
- Function `onStatusReshared`: original owner gets in-app row + push "shared your story" (skips self, gone/expired, allowSharing=false, blocked; idempotent). Push handler takes a custom `body`.
- Rules: `resharedFromOwnerUsername` validation; `forwardCount` may be incremented by any signed-in user (+1 only) — the old owner-only rule silently rejected the reshare/forward counter transaction.
- NOT done: server-side audience privacy for status reads (needs a data-structure change + migration).
