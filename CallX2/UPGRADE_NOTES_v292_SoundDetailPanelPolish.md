# v292 — Sound Detail panel polish + Follow button fix

Sound Detail screen/sheet (`fragment_sound_detail.xml` + `SoundDetailFragment.java`) mein 5 fixes:

## 1. Waveform seekbar box chota kiya
`card_sound_waveform` box compact kiya:
- Padding 14dp → 10dp
- Seekbar height 28dp → 20dp
- Waveform height 40dp → 26dp
- Top margins bhi thoda kam kiye (12dp→8dp, 16dp→14dp)

## 2. Follow button — sheet mein dikhta tha, screen mein nahi (FIXED)
**Root cause mila:** Sound reel/feed se bottom-sheet ke roop mein khola jata hai to
`creatorUid` seedha reel data se milta hai (`reel.uid`). Lekin full-screen
`SoundDetailActivity` (MusicPicker, SoundSearch, Trending Audio "Music" tab,
Saved Sounds, Playlist, Remix, Upload flow — in sab jagah se) khola jata hai to
wahan track `sounds/` node mein nahi, `musicLibrary/` node mein hota hai — aur
purana fallback sirf `sounds/{id}/creatorUid` check karta tha, jo musicLibrary
tracks ke liye kabhi exist hi nahi karta. Isliye creator row + Follow button
kabhi visible hi nahi hote the screen mode mein.

**Fix:** `loadSoundDataFromMusicLibrary()` mein ab `uploadedByUid` /
`uploadedByName` (MusicTrack model ke actual Firebase fields) se bhi creator
resolve hota hai, jab `sounds/` node se kuch na mile. Ab Follow button dono
modes (sheet + screen) mein consistently dikhega.

## 3. Top-left avatar/photo corner — bahut zyada rounded tha
Sound cover image (`iv_sound_cover`, top-left) ka corner radius 28dp tha
(pehle 14dp→20dp→28dp badhaya gaya tha) — bahut zyada round lag raha tha.
Ab **16dp** kar diya — subtle rounded-square, circle jaisa nahi.

## 4. Use in Camera / Use with Video / Add to profile — ab ek hi row mein
Pehle "Use in Camera" + "Use with Video" ek row mein the, aur "Add to profile"
neeche alag full-width row mein tha. Ab teeno same row mein, equal-width
(weight 1) boxes. Fit karne ke liye labels chote kiye: "Camera", "Video", "Add"
(icons wahi rahe, text size 12sp→11sp).

## 5. Original creator row — ab box mein (bg_sound_detail_box reuse)
Creator row pehle plain selectable list-row tha (sirf ripple background).
Ab **bg_sound_detail_box** drawable reuse kiya (wahi box jo waveform card aur
use-sound buttons use karte hain), taaki poore panel mein ek consistent
"box" look aaye. Ripple `android:foreground` se preserve kiya. Neeche wala
separate hairline divider (`divider_creator`) hata diya — box khud hi visual
separation de deta hai, divider redundant tha.

## 6. Details panel background — UserReelsActivity jaisa
`layout_sound_info` (poora details panel card) ka background pehle
`sound_detail_panel_bg` (hardcoded #FFFFFF light / #0F0F0F dark) tha. Ab
**`?attr/colorSurface`** — same attribute jo `UserReelsActivity`'s screen
background use karta hai — light aur dark dono mode mein exact match.

## Files changed
- `feature-reels/src/main/res/layout/fragment_sound_detail.xml`
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailFragment.java`
