# v295 — Camera/Video button corners + Add-to-profile text fix

## 1. "Use in Camera" / "Use with Video" corner radius matched to "Add to profile"
Dono buttons pehle `bg_sound_detail_box` use kar rahe the (12dp corner radius,
same drawable jo waveform card aur boxed creator row bhi use karte hain).
Ab naya drawable **`bg_sound_detail_action_btn.xml`** banaya — same fill
color (`sound_detail_button_light`) but **8dp corner radius**, exactly
`bg_btn_follow_pill` (Add to profile ka background) jaisa. Waveform
card/creator row ka radius (12dp) untouched hai — sirf inhi do buttons ka
drawable badla.

## 2. "Add to profile" text ab poora dikhta hai
Pehle teeno button equal-weight (1:1:1) the, isliye "Add to profile" text
"Add" tak short karna pada tha aur tap ke baad ka confirmation text
("✓ Added to profile") bhi cut ho sakta tha. Fix:
- **Add to profile** button ko zyada weight diya (1.3, Camera/Video ko 0.85
  each) — teeno ka total same 3.0 rehta hai, bas Add to profile ko zyada
  room milta hai.
- Text wapas full **"Add to profile"** (10sp, single line, ellipsize safety)
- Tap ke baad confirmation text `"✓  Added to profile"` se chota kar ke
  `"✓ Added"` kiya — guaranteed fit, kabhi truncate nahi hoga.

## Files changed
- `feature-reels/src/main/res/drawable/bg_sound_detail_action_btn.xml` (new)
- `feature-reels/src/main/res/layout/fragment_sound_detail.xml`
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailFragment.java`
