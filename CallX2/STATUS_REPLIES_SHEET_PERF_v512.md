# v512 — Status public-comments sheet perf pass (items 1–5)

1. **RecyclerView + ListAdapter/DiffUtil** (`StatusRepliesBottomSheet`) — no more `removeAllViews()` + rebuild per live update; stable ids, item animator off, live events coalesced into one submit per frame.
2. **Lazy inline reply box** — EditText/send button/TextWatcher are created only on the first "Reply" tap of a row holder (was 3 extra views per comment).
3. **Query-bounded listeners** — sheet: `ChildEventListener` on `orderByChild("timestamp").limitToLast(30)` + keyset pagination on scroll (`endAt(oldestTs).limitToLast(31)`); viewer bubble: `limitToLast(3)`. Manual field parse instead of `getValue(Class)`. Header shows `Comments (30+)` while older pages remain.
   **DEPLOY REQUIRED:** `".indexOn": ["timestamp"]` added under `status/$uid/$statusId/replies` in `firebase_rules_deployed_updated.json` and `firebase_rules/firebase_security_rules_UPDATED.json` → publish rules in Firebase console *before* shipping the app (without it queries still work but filter client-side).
4. **Avatars**: `AvatarUrlBuilder.buildResponsive(TINY)` + `override(tierPx)` + RGB_565 + `DiskCacheStrategy.RESOURCE` (same as `ReelCommentAvatarBinder`) → shared Glide cache key with reels comments. Applied in the sheet rows, the viewer's last-reply bubble, and the compose sheet's own avatar.
5. **Compose sheet thumbnail**: `override(52dp)` instead of 480×853.
Also: theme colors / typeface resolved once per sheet (was 4× `obtainStyledAttributes` per row).

Note: `onChildRemoved` of a `limitToLast` window also fires when a new comment pushes the oldest row out of the window; the sheet re-checks the key before dropping it, so paginated older rows are not lost.
Not done (not in scope): `replyCount` counter, PrecomputedText, background parse, prewarm, keepSynced, optimistic close, canvas row.

# v513 — items 6, 7, 8
6. `themeColor()`/`Typeface.create` — resolved once per sheet (`Style`), already done in v512; rows no longer touch the theme.
7. **PrecomputedText**: comment body is laid out on the bg thread (`PrecomputedTextCompat.create` with params from a template TextView) and applied via `TextViewCompat.setPrecomputedText`; falls back to plain `setText` if params ever mismatch (e.g. seed rows).
8. **Off-main parse**: sheet (child events, pagination pages, list sort) and viewer bubble listener parse snapshots on a single bg thread (manual field reads), then post to main; stale results dropped via generation counter / listener identity check. DiffUtil already runs async in `ListAdapter`.

# v514 — items 9, 10, 11
9. **Prewarm** — `StatusReplyBottomSheet.prewarm()` (called once per viewer session from `updateLastReplyOverlay`): caches my profile (Post no longer waits on a network read) and pre-decodes my avatar at the compose sheet's TINY/RGB_565 key. Last-3 commenters' avatars are warmed by the viewer bubble bind (same cache key as the sheet rows).
10. **keepSynced** — `StatusRepliesBottomSheet.liveQuery()` (the sheet's exact `limitToLast(30)` query) is `keepSynced(true)` while a status is on screen and released in `detachRepliesListener()` (segment change / activity close) → sheet opens from local cache. Cost: up to 30 comments downloaded per status viewed, even if the sheet is never opened.
11. **Optimistic close** — Post closes the sheet immediately; failure → toast + sheet reopens with the typed text prefilled; offline → "will post when you're back online" toast. Replaces v511 "Posting…" ack-wait for the compose sheet (inline Reply rows in the list still lock during send).

# v515 — item 12: canvas comment row
New `StatusCommentCanvasView` (one View per comment, zero children) — same pattern as `ChannelPostCanvasView` + `ChannelPostLayoutPrewarmer`.
- Name/body `StaticLayout`s are built on the sheet's bg parser thread (`StatusCommentCanvasView.build`, own `Metrics` paint copy) and carried in `Row.built`; `onMeasure` only reads cached heights (lazy main-thread rebuild if the width differs, e.g. rotation). Row width is taken from the RecyclerView's real layout width (estimate before first layout).
- All Paints/dimens in one shared `Metrics` per sheet; `onDraw` allocates nothing.
- Avatar: Glide `CustomTarget<Drawable>` (same request + options → same cache key as before) drawn via cached circular `BitmapShader`; placeholder circle + 👤 glyph. Cleared on recycle.
- Hand-written: tap hit-testing (avatar/name → profile, Reply, Delete, row → owner chat), long-press (Copy/Delete), pressed ripple on owner rows, TalkBack (content description + Reply/Delete custom actions).
- Inline reply: can't live in a canvas → it is its own list item (`ComposerVH`, real EditText) inserted right under the row being replied to, only one at a time; draft/sending state in `State`. Replaces the v512 per-holder lazy box and v513 PrecomputedText (superseded by StaticLayout prebuild).
- Public `StatusRepliesBottomSheet.Row/StyleHolder` are package-private; no other callers changed.
