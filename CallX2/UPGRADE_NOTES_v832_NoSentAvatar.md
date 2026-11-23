# v832 — remove sender-side (own message) avatar
- `avatarEligible()`: false for sent rows -> `hasGroupSenderAvatar` never set on own bubbles -> no avatar column subtracted in `onMeasure` (own bubbles gain GROUP_AVATAR_SIZE_DP + GROUP_AVATAR_GAP_DP of width), no avatar bitmap load/draw/hit-test, no "|GA" in the size signature.
- `setMyPhotoUrl()`: stores value only (no prefetch, no row notifies).
- `onChatAvatarUrlChanged`, member-photos observer, `refreshGroupSenderRow`, `runSenderAvatarPrefetch`: skip own rows (no payload binds, no warm of own photo).
- Received-side avatars, reactor/seen-by/poll faces, status_seen rows: unchanged.
Not compiled offline — run CI.
