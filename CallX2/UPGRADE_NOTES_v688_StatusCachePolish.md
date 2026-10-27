# v688 — Status cache polish

1. **Quota:** status ka apna 120MB quota (300MB shared "other" cache me) — `StatusMediaManifest` url→bytes track karta hai; `hasRoom()` / `trimToQuota()` (soonest-to-expire pehle evict). Worker per-run 100MB budget; prefetch quota full hone par rukta hai, evict nahi karta.
2. **Muted/Hidden:** worker + tray dono skip (StatusMuteManager / StatusHideManager).
3. **Worker cancel:** `cacheVideoBlocking(..., CancelCheck)` — WorkManager stop hone par beech download me ruk jata hai.
4. **Fresh data:** worker contacts ke statuses seedha Firebase se laata hai (Room sirf fallback).
5. **Schedule:** CallxApp (app-init-bg) se bhi schedule — Status tab kholna zaroori nahi.
6. **Tray cap:** ek session me pehle 12 videos full, baaki sirf 2MB warm-up; quota check.
7. **User control:** StatusPrivacyBottomSheet me "Auto-download statuses on Wi-Fi" switch + "Clear status cache (X MB)".
8. **Logout/account switch:** `StatusCacheController.onAuthChanged` (CallxApp auth listener) → ReelCardMetaCache + tracked status videos + worker wipe.
9. **2MB → full upgrade:** `mMode` map; Wi-Fi par dobara call aane par partial video full ho jata hai.
10. **NOT done:** status images ka expiry eviction (Glide single-entry evict nahi karta; apna image disk cache + viewer ka Glide key path badalna padega — alag step).
