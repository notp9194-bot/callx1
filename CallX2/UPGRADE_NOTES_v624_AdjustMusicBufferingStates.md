# v624 — Adjust Music: buffering / loading states (polish item 9)

`ReelPhotoMusicTrimActivity` (Reels photo + Status) + `activity_reel_photo_music_trim.xml` + `AudioTrimWaveformView`.

- Load ke dauran poora control panel dim + disabled (waveform touch bhi band); sirf "Change" usable rehta hai.
  Load fail hone par bhi panel locked rehta hai, Change se dusra gaana chun sakte ho.
- 25s load timeout: spinner hamesha nahi ghoomta, "Audio load timed out" dikhta hai. Late prepare ho jaye to controls unlock ho jate hain.
- Preview: seek ab `OnSeekCompleteListener` ke baad hi play shuru karta hai (API 26+ par SEEK_CLOSEST). End-of-range check bhi seek
  complete hone ke baad chalta hai, isliye slow stream par galat loop trigger nahi hota.
- Buffering indicator: preview image ke upar spinner + preview button ke icon ki jagah spinner
  (seek aur mid-play rebuffer, `MEDIA_INFO_BUFFERING_START/END`, dono mein).
- `AudioTrimWaveformView.onTouchEvent`: disabled hone par touch ignore.
