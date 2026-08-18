# Avatar zoom — Telegram/Instagram-style circular dock animation (v276)

Extends the chat media viewer's Telegram-style "shrink into the thumbnail"
animation (`MEDIA_VIEWER_TELEGRAM_CLOSE_v222.md` / `_v275.md`) to every
avatar-zoom dialog in the app, reusing the same gesture/animation
machinery — nothing gesture-related was reimplemented.

## What changed

`core/utils/DialogFullscreenHelper.java` (the single consolidated
`showAvatarZoom()` used by all 7 call sites) now:

- Accepts an optional `sourceView` — the avatar `ImageView`/`CircleImageView`
  that was tapped.
- **Open:** the photo starts scaled/positioned exactly over that avatar's
  on-screen spot as a full **circle**, then expands to full-screen while
  un-rounding into a square — the Instagram "tap the profile photo" feel.
- **Close** (close button, back-press, tap-outside, or swipe up/down —
  all four paths): the photo shrinks back into that same circular spot,
  Telegram-style, instead of just cutting away.
- **Swipe-to-close** is now the same advanced `MediaSwipeReplyCloseHelper`
  the chat media viewer uses (live scale/radius/alpha/scrim during drag,
  velocity-based fling-to-dismiss, physics spring snap-back) — replaces
  the old, more basic `AvatarZoomSwipeHelper` (single-fling-only, no live
  drag feedback), which is now deleted as fully superseded.
- If no `sourceView` is supplied (or it hasn't been laid out yet), falls
  back to the old plain instant show/dismiss — safe no-op, never crashes.
  `ReelCommentsAdapter`'s comment-photo tap (not a real avatar) keeps
  using this fallback path unchanged.

## Call sites updated to pass the tapped avatar view

All 7 existing `showAvatarZoom()` callers now pass the avatar `View`
through:
- `ProfileActivity` (own avatar, long-press)
- `UserProfileActivity` (partner avatar, tap)
- `CallsFragment` (call-history avatar, tap — both the inline and
  history-sheet variants)
- `ChatsFragment` (chat-list-row avatar sheet, tap)
- `ChatListAdapter` (chat row avatar, long-press)
- `ReelUserProfileSheet` (profile-sheet avatar, tap — both the CallX
  photo and YouTube-photo variants)
- `UserReelsActivity` (reel profile header avatar, long-press)

## Net result

Same wiring/call shape as before at every call site (just one extra
`View` argument) — only the dialog's open/close *animation quality*
changed, and it's driven by the exact same helper classes
(`MediaSwipeReplyCloseHelper`, `MediaViewerSourceRect`) the chat media
viewer already used, so the two features can't visually drift apart from
each other over time.
