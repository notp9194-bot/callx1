# v100 — Link Preview Flicker Fix (chat list junk on send/receive)

## Bug
Jab chat me koi URL (YouTube ya koi bhi link) hota tha, uske baad **koi bhi naya
message send/receive karne par poori visible chat list flicker/junk karti thi**.
Bina link ke chats me ye issue nahi tha.

## Root cause
`MessagePagingAdapter` ke Canvas bind path (`bindText()`) me link-preview card
har bar **unconditionally `cv.clearLinkPreview()`** call karta tha, phir
`LinkPreviewFetcher.fetch()` ke async callback se card wapas set hota tha.

Problem: `fetch()` cache-hit ke case me bhi result **hamesha `mainHandler.post()`**
se deliver hota hai — kabhi synchronously nahi. Toh:

1. `clearLinkPreview()` → card height 0 ho jata hai (`requestLayoutIfSizeChanged()`
   ek real size-change dekhta hai → `requestLayout()` fire hota hai).
2. Ek frame baad `setLinkPreview()` → card wapas apni height le leta hai →
   phir se `requestLayout()`.

Ye collapse → expand cycle **har rebind** par hota tha — aur ye row rebind
hoti hai har baar jab `reanchorPagingToBottom()` (har send/receive par)
`currentKeysetSource.invalidate()` call karke poore visible page ko fresh
`Message` objects ke sath reload karta hai.

Bubble ki height baar-baar jump karne se RecyclerView ko us row ke neeche
ki saari rows reflow karni padti hain — yahi hai "poori chat list
flickering/junk" jo sirf tab dikhti hai jab screen par koi link-preview
card maujood ho.

(Legacy non-Canvas ViewStub path is bug se safe tha kyunki wo loading ke
dauraan card ko `INVISIBLE` rakhke space reserve karta hai, `GONE`/collapse
nahi karta — sirf naya Canvas rendering path affected tha.)

## Fix
1. **`LinkPreviewFetcher.peek(url)`** — naya synchronous, cache-only lookup
   method add kiya (no network, no executor hop, no `mainHandler.post()`).
2. **`MessagePagingAdapter.bindText()`** — ab pehle `peek()` se cache check
   karta hai:
   - **Cache hit** (common case — link ek baar resolve ho chuka hai) → seedha
     usi frame me `setLinkPreview()` render hota hai, koi `clearLinkPreview()`
     collapse step hi nahi — koi height jump nahi, koi reflow nahi.
   - **Cache miss** (bilkul naya URL) → purana behavior hi rehta hai:
     `clearLinkPreview()` phir async `fetch()`.
3. Thumbnail-bitmap render logic ko dono paths (sync cache-hit + async fetch
   callback) ke liye ek shared `bindLinkPreviewResult()` helper me nikala,
   taaki duplicate code na ho aur dono paths identically render karein.

## Files changed
- `feature-chat/src/main/java/com/callx/app/utils/LinkPreviewFetcher.java`
  — added `peek(String url)`
- `feature-chat/src/main/java/com/callx/app/conversation/MessagePagingAdapter.java`
  — Canvas link-preview bind logic now cache-peek-first; added
    `bindLinkPreviewResult()` helper

## Expected result
- Pehli baar jab link wala message aaye/bheja jaye: waisa hi behavior (card
  fetch hone ke baad pop-in hota hai — koi reserved-space nahi, jaisa pehle
  tha).
- Uske baad jab bhi koi **aur** message send/receive ho: link-preview wali
  row cache se instantly render hoti hai, koi collapse/expand nahi, koi
  chat-list-wide flicker/junk nahi.
