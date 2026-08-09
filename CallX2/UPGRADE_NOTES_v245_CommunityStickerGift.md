# v245 — Community: Keyboard Sticker + Gift Send

Adds the ability to send a sticker (straight from the keyboard) or a gift
inside a Community post's comment thread (`CommunityPostCommentsActivity`).
Per the request, **no new sticker pack or gift catalog was added** — both
reuse mechanisms that already existed elsewhere in the app.

## 1. Keyboard sticker

`et_comment` is now a `GifAwareEditText` instead of a plain `EditText` —
the same class `ChatActivity`/`GroupChatActivity` already use to receive
Gboard's built-in sticker/GIF tray via Android's `commitContent` API. No
in-app sticker picker UI was added; whatever the keyboard hands over gets
uploaded (Cloudinary, `callx/sticker` folder — same as chat) and posted as
a comment with `type: "sticker"` + `mediaUrl`, rendered via the new
`iv_comment_sticker` ImageView in `item_comment.xml`.

## 2. Gift

A gift button (`btn_send_gift`, new `ic_gift.xml` icon) sits next to the
comment box. Tapping it lists the same five gift types
`ReelGiftingActivity` already displays on the creator earnings screen
(💎 Diamond / 👑 Crown / ⭐ Star / 🌹 Rose / 🎁 Gift, same coin values) — no
new catalog, just the existing one wired to a send action.

Sending a gift:
- Writes to `reelGifts/{postAuthorUid}/{eventId}` — the exact node
  `ReelGiftingActivity` already reads, so a gift sent from a community post
  shows up on the recipient's earnings screen too.
- Also posts a `type: "gift"` comment into the thread so other members see
  "🎁 sent a Diamond gift (+5000 coins)" inline.
- Blocked for self-gifting and when the post's author uid isn't available.

**Bug fix bundled in:** `CommunityFeedFragment.onComment()` was passing
`post.authorName` as `EXTRA_POST_AUTHOR` — a display name, not a uid. It
was unused until now; gifting needs the real uid, so it now passes
`post.authorUid`.
