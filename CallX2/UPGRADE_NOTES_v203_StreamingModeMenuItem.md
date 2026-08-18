# v203 — "Streaming Mode" menu item (⋮ 3-dot menu)

## Kya add hua
Reel player ke ⋮ (3-dot / "More") bottom sheet mein ek naya item add kiya:

**"Streaming Mode"** — dono flows (HLS Adaptive Streaming vs per-quality-URL
fallback) mein se abhi kaunsa is particular reel pe chal raha hai, wo batata hai.

Ye har reel ke liye ALAG dikhega — kyunki `hlsManifestUrl` per-reel set hota
hai (upload ke time Cloudinary ne HLS banaya ya nahi, uske hisaab se). Agar
tumne Adaptive Streaming add-on baad me enable/disable kiya, purane reels
apne upload-time ke behavior pe hi rahenge, naye reels naye behavior pe.

## Tap karne pe kya dikhta hai

**🟢 HLS Adaptive Streaming** (jab `reel.hlsManifestUrl` non-empty hai):
- "Playing via a single adaptive manifest (.m3u8)"
- "Quality switches happen in-place — no player rebuild"
- Current quality cap bhi dikhta hai (Auto/1080p/720p/etc.)

**🟡 Per-Quality URL (Fallback)** (jab `hlsManifestUrl` empty hai):
- "Playing via separate 480p/720p/1080p files"
- "Quality switches rebuild the player source" (chhota reload ho sakta hai)
- Explain karta hai ki ye normal hai jab Cloudinary Adaptive Streaming
  add-on account pe enabled nahi hai — kuch toota nahi hai
- Reminder: Cloudinary Dashboard → Settings → Add-ons check karo

## Files changed
- `ReelPlayerController.java` — naya `isHlsActive()` getter + naya
  `showStreamingModeInfo()` method (AlertDialog banata hai)
- `ReelPlayerDelegate.java` — interface mein `showStreamingModeInfo()` add
- `ReelPlayerFragment.java` — delegate method ko controller se wire kiya
- `ReelShareController.java` — naya `ACTION_STREAMING_INFO` dispatch case
- `ReelMoreBottomSheet.java` — naya `ACTION_STREAMING_INFO` constant, aur
  "Streaming Mode" item viewer + owner dono menu lists mein add kiya
  (existing `ic_speed` icon reuse kiya gaya hai, koi naya drawable nahi
  banaya)

## Test steps
1. Ek reel jo HLS-eligible account pe upload hui ho (hlsManifestUrl set)
   → uska ⋮ menu open karo → "Streaming Mode" tap karo → 🟢 green dialog
   aana chahiye.
2. Ek purani reel (pre-v202 upload, ya free-tier Cloudinary) → same tap →
   🟡 yellow "fallback" dialog aana chahiye.
3. Video Quality change karke phir Streaming Mode dialog dobara kholo —
   "Current quality" line update hona chahiye.
