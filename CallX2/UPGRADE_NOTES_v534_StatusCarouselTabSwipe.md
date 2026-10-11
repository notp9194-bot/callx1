# v534 — Carousel vs tab-swipe conflict

`NestedScrollableHost` (feed package) — Google ke ViewPager2 NestedScrollableHost sample ka port (ViewPager2 dependency ke bina, parent pager horizontal maana gaya). `item_status_carousel.xml` ka RV wrapper `FrameLayout` isse replace hua.
- Horizontal drag + carousel us direction me scroll kar sakta hai -> parents blocked (carousel scroll).
- Carousel start/end pe ho ya drag vertical ho -> parents intercept kar sakte hain (tab switch / list scroll).
- Scroll ke liye kuch na ho (kam cards) to sab gesture parent ko.
