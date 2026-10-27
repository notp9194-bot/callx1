# v687 — Status cache: WhatsApp-style

- **BUG FIX:** `StatusVideoCacheManager` ab `getOtherSimpleCache()` use karta hai (pehle REELS cache check hota tha, jabki STATUS factory OTHER cache me likhta hai → "already cached" check kabhi hit nahi hota tha, har baar re-download).
- **Full video on Wi-Fi:** `StatusMediaPreloader.cacheVideoBlocking()` — unmetered pe poora video (24MB cap), metered pe pehle 2MB. Skip-if-cached content-length ke saath.
- **Background pre-download:** `services/StatusMediaPrefetchWorker` — hourly, UNMETERED + battery-not-low, Room ke active, unseen contact statuses (newest first, max 60 / 8 min). `StatusFragment.onStart` schedule karta hai (KEEP).
- **Expiry eviction:** `cache/StatusMediaManifest` (url → expiresAt, prefs). `sweepAsync` (fragment onStart) + worker expired videos ko player cache se `removeResource` karta hai (5 min grace). Images Glide disk cache me hain — single-entry evict nahi ho sakta, LRU se jaati hain.
- **First-unseen jump:** `StatusViewerActivity.firstUnseenIndex()` — dusre ka story pehle unseen status se khulta hai (sab seen → shuru se). Target/highlight/single/own story untouched.
- Already tha (touch nahi kiya): tray preload (StatusFragment), image blur-up thumbnail (`thumbnailUrl` + `thumbHash`).
