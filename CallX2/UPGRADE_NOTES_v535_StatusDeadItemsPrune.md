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
