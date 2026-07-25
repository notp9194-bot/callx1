# v202 — HLS Adaptive Streaming Migration (Reels)

## Kya badla (full flow tumhare plan ke hisaab se)

**1. Upload side (Cloudinary HLS):**
- `index.js` → `/cloudinary/sign` endpoint ab `eager` param accept karta hai
  (pehle sirf `/cloudinary/sign/video` karta tha — asli reel-upload path
  `/cloudinary/sign` use karta hai, isliye ye badalna zaroori tha).
- `VideoUploader.java` → reel video upload (chunked + direct dono) ab
  `eager=sp_full_hd/m3u8` bhejta hai. Cloudinary is transform ko synchronously
  process karke response ke `eager[]` array mein HLS manifest URL deta hai.
- Read timeout 120s → 240s (HLS transcode synchronous hai, response late aata hai).

**2. ReelModel:**
- Naya field `hlsManifestUrl`. `video480/720/1080` fields **hataye nahi** —
  backward-compat fallback ke liye rakhe (purane reels + Cloudinary plans
  jahan Adaptive Streaming add-on enabled nahi hai, unke liye).

**3. Player side:**
- `AdaptiveStreamingManager` already `.m3u8` detect karta tha (`HlsMediaSource`)
  — usmein koi change nahi chahiye tha.
- `ReelPlayerController.preparePlayerSilently()`: agar `reel.hlsManifestUrl`
  present hai → seedha wahi URL player ko do, purana `pickQualityUrl()` +
  `preferAlreadyCachedQualityUrl()` cache-reuse patch skip. Agar empty hai
  (purana reel / HLS add-on off) → purana per-quality-URL path chalta hai
  jaisa pehle tha.

**4. Cache:**
- Ek hi manifest URL = ek hi cache-key family — CacheDataSource khud hi
  segments cache karta hai, resolution jo bhi dekha ho. Purana cache-reuse
  patch (`preferAlreadyCachedQualityUrl`) HLS reels ke liye ab zaroori nahi,
  isliye skip ho jaata hai (legacy reels ke liye abhi bhi active hai).

**5. Quality switch — naya, better than jo tumne manga tha:**
- Naya `AdaptiveStreamingManager.applyQualityCap(player, cap)` — HLS player
  ka quality cap **in-place** badalta hai (player stop/release/rebuild nahi
  karna padta, na hi naya network request). Stall-downgrade, network-upgrade,
  aur manual quality-lock — teeno ab HLS reels ke liye is fast path se hote
  hain (`switchToQuality()` mein guard laga hai). Legacy progressive reels
  ke liye purana rebuild-with-new-URL path waisa hi hai.

**6. Cleanup (partial — jaisa tumne "optional, baad me" bola tha):**
- `pickQualityUrl()` / `preferAlreadyCachedQualityUrl()` hataye nahi —
  legacy-fallback path ke liye zinda rakhe hain, bas HLS reels unhe bypass
  kar dete hain.
- `ReelVideoPreloader` / `ReelPredictivePreloader`: HLS reels ke liye purana
  byte-range preload **skip** kar diya (ek `.m3u8` text file ke raw bytes
  preload karne se koi video segment cache nahi hota — ExoPlayer khud
  segment-level prefetch handle karta hai). Duet-original preload (jo hamesha
  progressive MP4 hota hai) waisa hi chalta rehta hai.

## Zaroori: Cloudinary Adaptive Streaming add-on
`eager=sp_full_hd/m3u8` sirf tab kaam karega jab Cloudinary account pe
**Adaptive Streaming add-on** enabled ho — ye zyadatar paid plans mein hi
milta hai, free tier mein nahi. Agar enabled nahi hai:
- Upload **fail nahi hoga** — Cloudinary bas `eager` array response mein
  nahi dega, `hlsManifestUrl` empty save hoga, aur app automatically purane
  `video480/720/1080` flow pe fallback kar jayega (kuch tootega nahi).
- Cloudinary dashboard → Settings → Add-ons mein check kar sakte ho ki
  "Adaptive Bitrate Streaming" add-on laga hai ya nahi. Agar nahi hai to
  Cloudinary se add karwana padega (free trial bhi milta hai).

## Files touched
- `index.js`
- `core/src/main/java/com/callx/app/models/ReelModel.java`
- `core/src/main/java/com/callx/app/utils/VideoUploader.java`
- `feature-reels/src/main/java/com/callx/app/upload/ReelUploadActivity.java`
- `feature-reels/src/main/java/com/callx/app/player/AdaptiveStreamingManager.java`
- `feature-reels/src/main/java/com/callx/app/feed/controllers/ReelPlayerController.java`
- `feature-reels/src/main/java/com/callx/app/cache/ReelVideoPreloader.java`
- `feature-reels/src/main/java/com/callx/app/cache/ReelPredictivePreloader.java`

## Test karne ka tarika
1. Backend deploy karo (Render pe naya `index.js`).
2. Ek naya reel upload karo — Logcat mein `VideoUploader` tag dekho,
   `eager` response aaya ya nahi confirm karo.
3. Firebase mein us reel ke node mein `hlsManifestUrl` field check karo.
4. Reel play karo — pehli baar dekhte waqt buffering normal honi chahiye.
   Wapas peeche jaake dobara dekho (different network simulate karke, e.g.
   WiFi↔mobile data toggle) — ab bina re-download ke turant play hona chahiye.
5. Quality manually badlo (ReelABRSettingsActivity se) — badge turant badalna
   chahiye, koi naya buffering/stall nahi hona chahiye (in-place switch).
6. Purana reel (bina hlsManifestUrl ke) play karke confirm karo ki wahi purana
   behavior chal raha hai (regression check).
