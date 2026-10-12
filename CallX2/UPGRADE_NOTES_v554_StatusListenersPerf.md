# v554 — Status tab: per-contact listeners, off-main parse, lighter card

## Data loading (StatusFragment)
- Whole-root `status` listener hata diya. Ab har watched uid (me + contacts, blocked skip) par `status/{uid}` listener. Sirf badla hua contact download/parse hota hai; non-contacts ka data ab fetch hi nahi hota.
- `getValue(StatusItem.class)` + sort + expiry filter ab `STATUS_IO` (shared single worker) par. Main thread par sirf per-uid final result merge hota hai (`applyUserStatuses`).
- `Executors.newSingleThreadExecutor()` per call (thread leak) hata ke static `STATUS_IO`. `loadFromRoom` + `saveToRoom` dono isi par.
- `saveToRoom`: `itemToEntity` loop ab background mein; 600ms debounce; stale-row cleanup tabhi chalta hai jab sab watched uids ka pehla snapshot aa chuka ho (partial data par Room rows delete na hon).
- `loadFromRoom` seed ab per-uid (jis uid ka live snapshot aa gaya usko overwrite nahi karta).
- Stale in-flight parse results generation token (`statusListenerGen`) se drop hote hain (onStop / re-attach).

## MainActivity (status badge)
- Ye bhi poore `status` root par tha — isse #1 ka bandwidth fayda khatam ho jata (Firebase root cache se serve karta). Ab contacts listener ke saath per-contact `status/{uid}` listeners sync hote hain (`syncStatusBadgeListeners`), `statusSeen/{me}` live listener, recount 60ms debounce.
- Behaviour change: badge ab sirf contacts ke unseen statuses gino (pehle non-contacts bhi count hote the, jo carousel mein dikhte hi nahi the).

## Card layout (item_status_card.xml)
- `MaterialCardView` + nested `FrameLayout` -> ek `FrameLayout` (`bg_status_card_body` + `clipToOutline`).
- 3 overlay views (top scrim, bottom scrim, inner stroke) -> 1 view, `bg_status_card_overlay` (layer-list).
- StoryReshareActivity ke stub ids / root unchanged; sirf visual test karna hai.

## First-scroll jank (StatusListAdapter)
- Carousel setup par `AsyncLayoutInflater` se 5-7 cards pehle se inflate (`preInflate` / `obtainCardView`), recycled pool max 10.
- `onBindViewHolder`: avatar tab rebind nahi hota jab holder par wahi URL pehle se real (attached) bind ho chuka ho (`avatarKey`).
- feature-status/build.gradle: `androidx.asynclayoutinflater:asynclayoutinflater:1.1.0` (feature-chat wala same version).

## Firebase rules
- Koi change nahi chahiye: `status/.read = auth != null` root par hai, to per-uid read already allowed.
- Agar future mein rules tight karo (sirf contacts read), to `status/$uid/.read` par move karna padega — ye per-uid listeners ke saath compatible hai.
