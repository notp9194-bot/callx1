# Reels Display Mode — Immersive vs Normal (v182)

## What's new
Reels ka display mode ab user choose kar sakta hai:

1. **Immersive (Full Screen)** — pehle jaisa hi behavior. Reels tab par jaate
   hi status bar aur app ka bottom navigation dono hide ho jaate hain
   (TikTok-style full screen). Swipe from edge se temporarily reveal ho sakte
   hain.
2. **Normal** — status bar aur bottom navigation Reels tab me bhi dikhte
   rehte hain.

## Flow
- **First time** user Reels tab par jata hai (app-lifetime me sirf ek baar),
  ek non-cancelable bottom sheet dikhta hai jisme dono option milte hain.
  Jo bhi choose kare, wahi turant apply ho jata hai.
- **Change anytime** — Reels player ke 3-dot (⋮) menu me naya
  **"Display Mode"** option add kiya gaya hai (viewer aur owner dono menu
  me). Isse bottom sheet dobara khulta hai, current selection par ✓ mark ke
  saath, aur naya choice turant apply ho jata hai — chahe user Reels tab par
  hi kyu na ho.
- Selection SharedPreferences (`callx_prefs` → `reel_display_mode`) me
  persist hota hai, so app restart ke baad bhi yaad rehta hai.

## Files added
- `core/.../utils/ReelDisplayModePrefs.java` — shared prefs helper
  (`:core`, taaki `:app` aur `:feature-reels` dono access kar sakein bina
  circular module dependency ke)
- `feature-reels/.../social/ReelDisplayModeBottomSheet.java` — chooser UI
  (first-visit + 3-dot menu reuse karta hai)
- `feature-reels/.../feed/ReelDisplayModeListener.java` — cross-module
  callback interface jisse `ReelPlayerFragment` seedha `MainActivity` ko
  notify kar sake mode-change ka (bina `:app` par compile-time dependency
  liye)
- New drawables: `ic_display_mode_immersive`, `ic_display_mode_normal`,
  `ic_display_mode_check`, `bg_display_mode_option`,
  `bg_display_mode_option_selected`, `bg_display_mode_icon_badge`
- New layout: `bottom_sheet_reel_display_mode.xml`

## Files modified
- `ReelMoreBottomSheet.java` — naya `ACTION_DISPLAY_MODE` action + menu item
  (viewer aur owner dono list me, Download ke turant baad)
- `ReelPlayerDelegate.java` — naya `showDisplayModePicker()` method
- `ReelShareController.java` — `ACTION_DISPLAY_MODE` ko delegate tak dispatch
  karta hai
- `ReelPlayerFragment.java` — `showDisplayModePicker()` implement kiya +
  `ReelDisplayModeBottomSheet.OnModeSelectedListener` se selection receive
  karke `MainActivity` ko forward karta hai
- `MainActivity.java` —
  - Reels tab par first visit par chooser dikhata hai
    (`ReelDisplayModePrefs.hasBeenAsked()` check)
  - `setMainNavVisible(...)` calls (onPageSelected + onResume) ab hardcoded
    "hide on Reels" ki jagah saved mode follow karte hain
  - `ReelDisplayModeListener` + `ReelDisplayModeBottomSheet.OnModeSelectedListener`
    dono implement karta hai taaki mode-change turant reflect ho, chahe wo
    first-visit chooser se aaye ya 3-dot menu se
