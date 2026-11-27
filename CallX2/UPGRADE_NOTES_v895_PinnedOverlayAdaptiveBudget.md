# v895 — Pinned label overlay + adaptive shared-node budget

Build/run nahi hua (Gradle + javac yahan nahi): sirf bracket-balance + manual review. Device pe test karo.
(v894 ke upar.)

## Kya badla (`MessageBubbleCanvasView`)
1. **Pinned label overlay me** — `drawPinnedLabel` ab `drawDynamicOverlayLayer` me chalta hai, node me baked nahi.
   `setPinned` ab node tabhi dirty karta hai jab height badli, warna overlay redraw. Shared key (`computeSharedDecor`) se pinned hata,
   to pinned/unpinned bubble ek hi node share kar sakte hain.
2. **Adaptive shared-node budget** — `initSharedBudget()` device memory class se `SHARED_NODE_MAX` (192) aur weight (288) scale karta hai:
   low-RAM 0.5x, <192MB 0.75x, 192+ 1x, 256+ 1.5x, 384+ 2x. `onTrimMemory` ka /4 trim bhi isi budget pe.

## Jaan-bujhke nahi kiya
- Tail overlay me: tail bubble drawable ke path ka hissa hai, alag nahi hota.
- Cards/media ko shared cache me: key me har baked input (bitmap, rects, colors, text) sahi aani chahiye. Bina compile/device ke galat visual ka risk.
- Pre-record / prefetch / text-layout precompute: pehle se hai (v827, v107/v376/v378, `AdaptiveChatScrollPolicy`, `calculateExtraLayoutSpace`).
