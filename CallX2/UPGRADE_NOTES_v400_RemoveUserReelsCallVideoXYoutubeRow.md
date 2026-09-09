# v400 — UserReelsActivity: Removed Call / Video Call / X / YouTube action row

## What changed
Removed the "Call | Video Call | X | YouTube" quick-action icon row entirely
from the reels user-profile screen (`UserReelsActivity`).

### XML — `feature-reels/src/main/res/layout/activity_user_reels.xml`
- Deleted the visible `layout_extra_actions` row (`btn_call_row`, `btn_video_call`,
  `btn_open_x`, `btn_open_youtube` + their `iv_anim_x` / `iv_anim_youtube` peek icons).
- Deleted the matching dead/hidden legacy entries (`btn_audio_call`, video call,
  X, YouTube) from the hidden `layout_actions` bar — only `btn_message` remains
  there since Message is unrelated to this removal.

### Java — `feature-reels/src/main/java/com/callx/app/profile/UserReelsActivity.java`
- Removed field declarations: `btnAudioCall`, `btnVideoCall`, `btnOpenX`,
  `btnOpenYoutube`, `btnCallRow`, `layoutExtraActions`, `ivAnimX`, `ivAnimYoutube`.
- Removed the corresponding `findViewById()` calls.
- Removed the `layoutExtraActions` visibility toggle in `isSelf` setup.
- Removed the click listeners for Audio Call, Video Call, X profile, YouTube
  channel, and the row's Call button (all inside `setupActionButtons()`).
- Removed the Firebase avatar-fetch listeners for the X (`x/users/{uid}`) and
  YouTube (`youtube/channels/{uid}`) peek icons.
- Simplified the avatar "peek" animation loop (`startAvatarPeekLoop()` /
  `stopAvatarAnimation()`) to only animate the Message avatar (`ivAnimChat`),
  since the X/YouTube icons it used to cycle through no longer exist.

## Not touched
- The bio-link "YouTube channel" text row (`layout_youtube` / `tv_youtube`) —
  that's a different feature (profile bio link display), left as-is.
- Call/video-call buttons on other screens (Calls tab, Contacts, Chat list,
  reel bottom-sheet profile, etc.) — out of scope, untouched.
