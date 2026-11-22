# v818 — Reply bar: mic-recording-UI optimizations reused

Recording bar me jo perf patterns the, wahi ab reply row (capsule ke andar) me bhi.

| Recording UI me | Reply UI me ab |
|---|---|
| Lazy ViewStub (inflate on first use) | Already (v817, stub capsule ke andar) |
| Single-pass custom measure/layout (`ChatInputBarContainer`) | Already (v817, reply row same container me) |
| Capsule clip-bounds reveal (no per-frame layout) | Already (v817; group me bhi `CapsuleRevealAnimator`) |
| Cached `LayoutRecordingBarBinding` (no findViewById) | `ReplyBarLayout` children `onFinishInflate` me ek baar cache; `ReplyPreviewBinder` unhi ko use karta hai |
| Hardware layer sirf animation ke dauran (v372 dot blink) | Enter/exit animation `.withLayer()` — texture animation ke baad auto release |
| — | `onLayout` me har pass par Rect+TouchDelegate allocate hota tha -> ab Rect reuse, delegate sirf rect badalne par |
| — | `setText` skip jab text same ho (retarget), `setTextColor` skip jab same |
| — | Lambe message (4000 chars) ka preview 240 chars par cut, taaki TextView poora measure na kare (sirf 3 lines dikhti hain) |

Files: `ReplyBarLayout.java`, `ReplyPreviewBinder.java`.
