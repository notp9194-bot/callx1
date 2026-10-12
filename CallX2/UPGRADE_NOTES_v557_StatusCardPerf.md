# v557 — Status carousel card: fastest-path perf (image bytes, decode, pre-warm, placeholder, bind cost)

## 1. Card image: exact-size CDN crop (sabse bada fayda)
- `AvatarUrlBuilder.buildCard()` (core): `w_{dp},h_{dp},c_fill,g_auto,dpr_{1.5|2|3},q_auto[:level],f_webp` — CDN sirf card ke pixels bhejta hai.
- Pehle: media thumb "as-is" tha — naye photos ka 320px `c_limit` (card 300x480px chahiye = upscale/soft) aur purane statuses (thumbnailUrl nahi) par **poori `mediaUrl`** download hoti thi.
- Safe: non-Cloudinary / version-less / unknown-transform (video eager derivative) URL **unchanged** return hota hai. Purana `c_limit,w_N,h_N,q_auto,f_auto` chain replace hota hai (original se crop).
- WebP jaan-boojh ke (AVIF nahi): naye status ka first-hit derivative jaldi banta hai.
- `StatusCardAdapter.bgUrlFor`: URL ab card par memoized (`bgUrlCache`, ver = -2).
- `cardThumb(type, thumb, media)` ab single source of truth (bind + prewarm same URL).

## 2. Decode format
- `cardReq`: API 26+ par `PREFER_RGB_565` hata — app default (ARGB_8888 -> HARDWARE bitmap): first-draw texture upload nahi, banding nahi.
- RGB_565 sirf API < 26 / low-RAM device par. Kill switch: `StatusCardAdapter.CARD_HW_BITMAPS = false` (v556 behaviour).
- Verify: Layout Inspector / `bitmap.getConfig() == HARDWARE`, aur fling par jank (macrobenchmark).

## 3. Pre-warm (tab khulne se pehle)
- Naya `feed/StatusCardPrewarm`. `MainActivity.prewarmStatusCard()`: per-contact `status/{uid}` snapshot (badge listener) aate hi us contact ka latest live status thumb, bind wali exact request (same URL/size/format) se preload.
- Budget: 12 distinct URLs (LRU), Data Saver par skip, text status skip.

## 4. ThumbHash placeholder
- `StatusItem.thumbHash` (+ `toMap`). Upload ke time `StatusThumbHashHelper` (background) photo/video frame/thumbFile se ~25-byte hash banata hai; `NewStatusActivity.saveStatus` + `saveBatchStatus` node par likhte hain (generation guard: late result agle item me leak nahi hota).
- Card: flat grey ki jagah blurred ThumbHash (L1 hit = isi bind me; miss = off-main decode), real image aate hi crossfade. Existing `ThumbHashPlaceholder` reuse.
- Firebase rules me change nahi chahiye (`status/{uid}/{id}` par field whitelist nahi hai).
- NOT done: Room column (migration chahiye) — Room se seeded cards par hash nahi hota; live snapshot se aata hai. (Cold start me image Glide disk cache se aati hai.) Purane statuses par hash nahi — flat placeholder.

## 5. Bind micro-costs (StatusCardAdapter)
- `setMaxLines()` (requestLayout) ab `onCreateViewHolder` me ek baar.
- `describe()` (2-3 `getString(format)`/bind) hata: content description accessibility delegate me on-demand. (Behaviour: TalkBack ko focus par milta hai; diff update par live "content changed" announce nahi hota.)
- `ivBg.setBackgroundColor()` (naya ColorDrawable/bind) sirf hidden-tile se recycle hone par.
- Avatar URL + version ab bind me ek baar resolve, `CardItem.avUrlCache` me memoized.

## 6. Baseline profile
- `StatusCardPrewarm` add. Measured profile ke liye device par: `./gradlew :macrobenchmark:generateBaselineProfile`.

## Files
core: `AvatarUrlBuilder`, `StatusItem` · feature-status: `StatusListAdapter`, `StatusCardPrewarm` (new), `NewStatusActivity`, `StatusThumbHashHelper` (new) · app: `MainActivity`, `baseline-prof.txt`

## Test checklist
1. Naya photo status post karo -> doosre account ke carousel me blur turant, phir sharp image fade-in.
2. Purana status (thumbnailUrl nahi) -> network inspector me ab card-size WebP aata hai, full-size nahi.
3. Status tab kholne se pehle (Chats tab par) Glide requests dikhein; tab kholne par card instant.
4. Seen/muted dim, hidden tile, "Add status" tile, my status — visual regression check.
5. TalkBack: card par "Name, new status, 5m ago" + long-press action.
6. Low-end device / API < 26: RGB_565 path chalna chahiye.
