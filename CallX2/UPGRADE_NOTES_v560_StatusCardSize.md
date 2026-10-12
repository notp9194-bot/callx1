# v560 — Status card bada: 100x160dp -> 112x180dp (tablet 128x200 -> 140x224)

- Sirf `status_card_dimens.xml` (values + values-sw600dp). Layout, skeleton, bind, preloader, prewarm aur CDN crop (`buildCard` w_/h_) sab inhi dimens se chalte hain, to auto follow karte hain.
- 360dp screen par ~2.9 card dikhte hain (teesra aadha jhalakta hai). Aspect ratio ~5:8 same.
- Avatar ring 44dp, radius 16dp unchanged.
- Note: size badalne se card bg ka Glide cache key/CDN URL badlega (naya crop size) — pehli baar images dobara download hongi.
- Visual check: text-status preview padding (56dp top / 38dp bottom) aur "Add status" tile (+ badge) bade card me.
