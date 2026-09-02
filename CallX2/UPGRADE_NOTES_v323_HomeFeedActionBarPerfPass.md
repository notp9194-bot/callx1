# v323 — Home Feed action-bar/header Instagram-level perf pass

Follow-up to the earlier collab-row optimization pass. That pass only
covered the bottom-left collab icon and collab avatar-stack row — every
other per-bind listener/allocation in `HomeFragment`'s `PostRowHolder`
bind path was still being paid on every single bind (every card scroll,
every `notifyItemChanged()`). This pass covers the rest.

## What changed (`feature-reels/.../feed/HomeFragment.java`)

**Registered-once click listeners** (was: fresh lambda allocated on
every bind → now: registered once per physical `PostRowHolder`, reads
`holder.boundReel` / `holder.boundMyUid` at click time):
- Avatar tap, thumbnail tap, owner-name (`tvOwner`) tap — the latter
  now resolves the legacy-collab-vs-solo-owner branch fresh at click
  time instead of being decided once at bind time.
- Like, Comment (icon + count), Repost, Save, Send/Share, shares-count
  tap, More (⋮) overflow menu.
- Liked-by row, comment-preview row, "See translation" row.

New `actionBarListenersBound` flag on `PostRowHolder` — kept separate
from the existing `clickListenersBound` flag because the action-bar
code runs *after* `clickListenersBound` is already flipped true earlier
in the same bind method, so it needed its own one-time gate.

**Like/Save toggle state** moved off a per-bind `boolean[]` closure
onto the holder itself (`boundIsLiked` / `boundIsSaved`), since the
now-persistent listener needs somewhere durable to read and flip.

**Tagged-people & product-tag pills** — biggest change. These were
`removeAllViews()` + a brand-new `TextView` + a brand-new
`setOnClickListener` lambda for every tag, on every single bind.
Replaced with a per-holder pill **pool** (`obtainPoolPill()`): each
pill is created once, gets one shared allocation-free click listener
(reads `v.getTag()` at click time — a `String` uid for people pills, a
`ReelModel.ProductTag` for product pills), and subsequent binds just
update text/tag/visibility on the existing views. Unused pool slots
from a previous, longer-tagged reel are hidden, not destroyed, so
they're ready to reuse.

## Not touched
Everything the previous pass already covered (collab icon, collab
avatar-stack row) — untouched, still working as before.
