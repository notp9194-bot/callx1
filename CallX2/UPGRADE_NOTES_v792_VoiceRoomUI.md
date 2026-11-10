# v792 — Voice Rooms (UI only)

New module `feature-voicerooms` (package com.callx.app.voicerooms). NO backend/audio yet — demo data only (VrDemoData).

## Screens
- VoiceRoomsHomeActivity — live rooms list, category chips, search, "Start a room" sheet
- VoiceRoomActivity — screenshot clone: banner, topic, speakers (speaking-ring demo), audience grid, pinned msg, chat + heart reactions, gift/poll/music/people/more sheets, mic + raise hand

## Entry point
Chats tab 3-dot menu -> "Voice Rooms" (main_menu.xml + MainActivity).

## Drawable reuse
All icons are existing app drawables (core: ic_mic, ic_search, ic_poll, ic_group, ic_share_reel, ic_music_note, ic_waveform_mini, ic_send_fill, ic_verified_pink, ic_heart_filled, ic_play/pause, ic_volume_off, ic_logout, ic_close, ic_arrow_back, ic_more_vert).
Same-named copies (identical files) for icons that live only in other feature modules: ic_mic_off, ic_gift, ic_emoji, ic_add, ic_chevron_right, ic_chevron_down, ic_pin.
New: only vr_bg_* shape drawables (rounded cards/chips/rings) + vr_* colors (light + dark).

## Backend hook points (TODO(backend) comments in code)
- VrAdapters.bindAvatar -> Glide photoUrl
- VoiceRoomActivity.addMessage -> voiceRooms/{id}/chat
- gift send -> Cloud Function coin deduction
- shareRoom -> real deep link
