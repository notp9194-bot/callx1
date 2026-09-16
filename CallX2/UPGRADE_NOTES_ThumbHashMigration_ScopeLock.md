# ThumbHash migration (chat only) — Step 9: scope-lock verification

Steps 1–8 replaced BlurHash with ThumbHash on the **chat image/video path only**,
and removed the redundant 8px WebP micro-thumb stage for images. Step 9 is a
verification pass, not a code change — confirming nothing outside chat's
image/video path was touched.

## Confirmed untouched (still on BlurHash, as intended)

- `feature-reels/.../workers/BlurHashBackfillWorker.java` — `BlurHash.encode()`
- `feature-reels/.../feed/controllers/ReelUiController.java` — `BlurHashPlaceholder.get()`
- `feature-reels/.../profile/ReelGridAdapter.java` — `BlurHash.decode()`
- `feature-reels/.../upload/ReelUploadActivity.java` — `generateAndAttachBlurHash()` → `BlurHash.encode()`
- `feature-reels/.../profile/UserReelsActivity.java` — enqueues `BlurHashBackfillWorker`
- `app/.../activities/ProfileActivity.java` — avatar `BlurHash.encode()` (own avatar LQIP, not chat)
- `core/.../db/AppDatabase.java`, `HomeFeedCacheEntity.java` — `ownerAvatarBlurHash` schema (reel/community avatar cache)
- `core/utils/BlurHash.java`, `BlurHashPlaceholder.java` — kept as-is; still the live implementation for all of the above

## Confirmed swapped (chat only)

- `ChatMediaController.java` — image path (~L1573–1676) and video path
  (~L1944–2036) both call `ThumbHash.encode()`; no `BlurHash` import left
  in the file (only explanatory comments mentioning the old name)
- `MessagePagingAdapter.java` — image block (~L4363–4366) and video block
  (~L4668–4680) both call `ThumbHashPlaceholder.get()`; only import is
  `ThumbHashPlaceholder`
- `GroupChatActivity.java` — no direct BlurHash/ThumbHash calls of its own;
  it renders entirely through a `MessagePagingAdapter` instance
  (`pagingAdapter = new MessagePagingAdapter(...)`), so it inherited the
  swap automatically — step 4's separate "GroupChatActivity.java" line
  item is already satisfied, nothing left to edit there

## One deliberate divergence from the original step 7 wording (video)

For **images**, `thumbnailUrl` is now left null on send — the old value was a
throwaway 8px WebP micro-thumb, fully redundant once ThumbHash gives an
instant local placeholder, so removing it was a clean win (one less
Cloudinary upload + one less network round trip on receive).

For **video**, `thumbnailUrl` was **kept** on both sender and receiver:
`vr.thumbFile` (the ~300–480px extracted poster frame from
`VideoCompressor.makeThumbnail`) is not a disposable micro-thumb — it's the
only static preview of the video's actual content, and it's used for two
different things: (1) downsampled 4x → `ThumbHash.encode()` for the instant
placeholder, and (2) uploaded as-is → `thumbnailUrl`, which
`MessagePagingAdapter` loads via Glide once the ThumbHash placeholder is up.
There's no separate/duplicate small-thumb upload stage for video to remove —
unlike images, a video bubble has no "full-res" fallback to jump to on tap
(tapping starts playback/streaming, not a full download-and-decode), so the
poster frame stays load-bearing. Flagging this so it doesn't look like an
unfinished step 7.

## Net result

- Chat (1:1 + group, since group reuses the same adapter): BlurHash → ThumbHash,
  image micro-thumb stage removed, video poster-frame stage unchanged.
- Reels + profile avatars: fully untouched, still BlurHash end-to-end.
- No shared file (`BlurHash.java`, `Message.java`, `MessageEntity.java`,
  `MediaE2ECrypto.java`) had its BlurHash-serving behavior removed — only the
  chat-specific call sites were re-pointed to ThumbHash.
