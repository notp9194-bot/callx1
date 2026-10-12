# v555 — Status carousel: less rebind work, smarter preload

- StatusCardAdapter: bg image ab holder par `bgKey` (url@WxH) se track hoti hai. Same image dubara bind ho (DiffUtil: seen/mute/time change) to Glide request hi nahi banti. Failed load / text card / recycle par key reset.
- Card bg transition: cross-fade sirf `DataSource.REMOTE` (network arrival) par. Memory/disk hit par instant swap (extra draw pass nahi).
- Scroll preloader: fast fling (>28dp per scroll event) mein skip; scroll IDLE par visible window ke aas-paas preload.
- StatusFragment sweep: time-label rebuild ab tabhi jab koi visible label (minute/hour) sach mein badalta hai (`nextLabelChangeMs`); pehle har 60s full rebuild.
- baseline-prof.txt: StatusCardAdapter / CardItem / avatar binder / ring drawables / StatusFragment hot methods add. Measured profile ke liye: `./gradlew :macrobenchmark:generateBaselineProfile` (device chahiye).

## Jaan-boojh ke nahi kiye
- Card bg ke liye weak-ref L2 bitmap cache: Glide ka BitmapPool recycled bitmap ko dusre decode mein reuse kar sakta hai, to weak-ref se galat image dikh sakti hai.
- `diskCacheStrategy(RESOURCE)`: Glide ka default AUTOMATIC remote URLs ke liye pehle se resource + data dono cache karta hai.
- Ring drawables: `StoryRingGradientDrawable` pehle se cached bitmap blit hai, `StatusSegmentedRingDrawable` mein draw() mein allocation nahi. Hardware layer se fayda nahi.
