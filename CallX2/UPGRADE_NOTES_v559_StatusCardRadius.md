# v559 — Status card corner radius 22dp -> 16dp

- `status_card_radius` = 16dp (phone + sw600dp). Card 100x160dp par 22dp zyada pill-jaisa tha; 16dp modern/story-card look.
- `bg_status_card_body.xml` ab hardcoded 22dp ki jagah `@dimen/status_card_radius` use karta hai (ek jagah se control).
- clipToOutline isi drawable ke outline se chalta hai, to image/overlay/ring clip bhi 16dp ho jata hai.
- Radius badalna ho to sirf `status_card_dimens.xml` (values + values-sw600dp).
- Skeleton card (`bg_status_card_skeleton.xml`) bhi `@dimen/status_card_radius` use karta hai, taaki loading card real card se match kare.
