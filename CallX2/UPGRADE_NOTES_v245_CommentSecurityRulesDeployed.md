# v245 — Reel Comment Security Rules Actually Merged

Follow-up to v244. `firebase_reel_comments_rules.json` existed as a
standalone fragment but was never folded into the real deployed rules —
`firebase_rules_deployed_updated_v2.json` still had none of the
`reelComments` protections, and its root `.write` was a blanket
`"auth != null"`, which in RTDB cascades down and silently overrides any
narrower rule underneath it anyway.

## What changed in `firebase_rules_deployed_updated_v2.json`
- Merged in `reelComments`, `reelCommentReplies`, `reelCommentReports`,
  `reelReplyReports`, and `reels/$reelId/commentsCount` from the fragment
  file — same restrictions described in v244 (own-uid create, author-or-
  reel-owner delete, author-only edit/pin-by-owner, write-once reports,
  non-negative counters).
- Root `.read`/`.write` narrowed from blanket `"auth != null"` to `false`,
  so the merged rules above actually take effect instead of being
  bypassed by cascade.
- Because the root no longer grants a blanket write, `deliveryPending`
  (which previously only had a `.validate` and relied on the root
  cascade) now has its own explicit `.read`/`.write: "auth != null"` —
  same effective permission as before, just stated locally instead of
  inherited. `reelSeenDedup` / `broadcast_lists` / `broadcast_messages`
  already had their own explicit rules, so they're unaffected.

## To deploy
Paste the `rules` object from `firebase_rules_deployed_updated_v2.json`
into Firebase Console → Realtime Database → Rules, replacing what's
there now. Note this file only reflects the paths shown above — if your
live rules have additional top-level nodes from the other
`firebase_*_rules.json` fragments in this repo (chat, community, x,
duet, broadcast, e2e-rekey, etc.), merge those in too before removing
the blanket root write, or those paths will go from "open" to "closed"
unintentionally.
