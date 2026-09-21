# v439 — Avatars on reactions and poll votes (group chat)

Three places, one shared drawing helper (`canvas/MiniAvatarStrip`: overlapping circles + optional grey "+N" chip,
one cached BitmapShader per slot, flat grey circle while a photo is missing, key-guarded late bitmaps).

## 1. Reaction badge on the bubble
Up to 3 reactor avatars (14dp, 5dp overlap) right after the emoji text, vertically centred on the badge. Group chats only.
- Badge is right-anchored, so avatars only move its LEFT edge: `reactionAvatarsExtraWidth()` is folded into the badge width in
  `onMeasure`, `invalidateReactionsRegion()` and the tap hit-rect — no size-signature change, no re-measure, still the
  dirty-region path (badge lives in the dynamic overlay layer, never in the cached bubble).
- API: `setReactionAvatars(key,count)` / `setReactionAvatarBitmap(key,slot,bmp)` / `clearReactionAvatars()`;
  `clearReactions()` drops them too. Adapter: `bindReactionAvatars()` from the full bind and the `FLAG_REACTIONS` fast path.
- No "+N" chip: the badge text already carries the counts ("😍2").

## 2. Reactions dialog (`showGroupReactedUsers`)
Each row now has the member's circular avatar (`buildMemberAvatarRow`, `ChatAvatarBinder` pipeline — shares L2/L3 with the
Read-by / member lists). NB: the list previously had only emoji + name; it did not have avatars.

## 3. Poll voters (group, NON-ANONYMOUS only)
- Strip in the poll card's existing "N votes" footer row, right-aligned (≤5 voters → all, more → 4 + "+N").
  Layout-neutral (the footer row already exists — no card-width / option-text-width change), drawn by `PollRenderer` via
  `host.drawPollVoters()`, which also records `pollVotersRect` as the tap target (padded ±8dp).
- **Anonymous polls never show it**: gated in `bindPollVoters()` (`pollAnonymous`) AND again in `showGroupPollVoters()`.
- Tap → new `OnBubbleClickListener.onPollVotersClick()` → `ActionListener.onPollVotersTap(m)` → `GroupChatActivity.showGroupPollVoters()`:
  "Poll votes" dialog, per option a bold header (`option · N`) then that option's voters with avatars (multi-choice voters appear under each option they picked).
- Per-option avatars were deliberately NOT drawn inside the option rows: they'd shrink the option text (already ~122dp) or
  force a relayout on every vote (the live-vote path is intentionally relayout-free). The dialog gives the per-option split instead.
- Kept fresh on live votes: `bindPollOnly()` (FLAG_POLL) re-binds the strip.

## Photo changes
`onMemberPhotosChanged()` now also re-binds rows whose reactions / (non-anonymous) poll votes include a changed uid, via ONE combined
`Integer` flags payload (the flags path only reads payload element 0). Strip keys embed each photo hash, so only changed faces reload.

Files: canvas/MiniAvatarStrip.java (new), canvas/MessageBubbleCanvasView.java, canvas/PollRenderer.java,
canvas/OnBubbleClickListener.java, MessagePagingAdapter.java, group/GroupChatActivity.java
Not compile-tested here (no Android SDK/Gradle) — Java parse-checked only; run a normal build.
