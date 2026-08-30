# v294 — Sound Detail: header + reels grid background = panel card colour

`fragment_sound_detail.xml` mein do backgrounds ab panel card
(`layout_sound_info`) jaisa hi `?attr/colorSurface` use karte hain, light aur
dark dono mode mein:

1. **Top bar (header)** — pehle hardcoded `sound_detail_bg_light` (#FFFFFF
   light / #0F0F0F dark) tha, ab `?attr/colorSurface`.
2. **Reels grid (`rv_sound_reels`)** — same fix, pehle `sound_detail_bg_light`
   tha, ab `?attr/colorSurface`.

Ab header, panel card, aur reels grid — teeno exact same surface colour pe
baithte hain, jaise `UserReelsActivity` ka screen background.

## Files changed
- `feature-reels/src/main/res/layout/fragment_sound_detail.xml`
