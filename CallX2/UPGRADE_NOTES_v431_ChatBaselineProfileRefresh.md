# v431 — Chat Baseline Profile Refresh (AOT coverage fix)

## Problem
Baseline profile chat ke naye hot methods (v427-v430: `invalidateReactionsRegion`,
`invalidatePollRegion`, `setViewingDot`, ...) cover nahi karta tha. Source se
cross-check karne pe **bada root cause** mila:

- `MessagePagingAdapter` / `ChatListAdapter` ke rules `onBindViewHolder(RecyclerView$ViewHolder;I)V`
  (erased bridge) pe likhe the. Real methods `onBindViewHolder(MessagePagingAdapter$VH;I)V` hain —
  to profile sirf chhota synthetic bridge compile kar raha tha, **asli bind body (~4k lines) AOT nahi tha**.
- 42 rules dead the (class/method exist hi nahi karta: `SplashActivity`, `LoginActivity`,
  `MessageAdapter`, `ChatViewModel`, `TypingDotsAnimator`, `ChatActivity.initViews()`,
  `call.CallActivity` typo, ...). ART inhe silently ignore karta hai.
- `FastFlingRecyclerView` / `RubberBandEdgeEffectFactory` (chat fling path) ka koi rule nahi tha.

## Fix (`app/src/main/baseline-prof.txt`)
- 42 dead rules hataye.
- ~2250 exact-signature rules add kiye (sources se parse karke, JVM descriptors ke saath):
  canvas bubble view + sab renderers, `MessagePagingAdapter` (+ nested VH), `ChatActivity`,
  chat controllers, fling/edge-effect, swipe-reply, input bar/reply UI, bind-path helpers
  (`MediaCache`, `ThumbHash*`, `ChatThemeManager`, ...), group chat, chat list (startup flag ke saath).
- `HP` flags chat-screen ke liye (startup profile pollute nahi hota); `HSP` sirf chat list + startup classes.

## Note
Ye static (source-derived) profile hai — device pe measure nahi kiya. Phir bhi ek baar
`./gradlew :macrobenchmark:generateBaselineProfile` chalao (measured + `startup-prof.txt` bhi banega).
Wo output is file ke saath merge hota hai.
