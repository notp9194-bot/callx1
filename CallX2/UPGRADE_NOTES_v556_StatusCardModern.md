# v556 — Status carousel card: modern, flat, no extra cost

- Avatar ab card ke ANDAR top-start (8dp margin); pehle card ke upar overlap karta tha (22dp overlap hata, 4dp elevation layer hata).
- Ring 1.5dp (stroke) + 2dp transparent gap + avatar chhota: ring box 44dp -> avatar 37dp (avatar = ring - 2*(1.5+2)). Tablet (sw600dp): 52dp / 45dp. Dimens: `status_card_avatar_ring`, `status_card_avatar`.
- Card 100x160dp (tablet 128x200), corner 22dp, stroke + inner hairline + elevation hata. Row par ~3.2 card dikhte hain (agla card jhalakta hai).
- Overlay: top vignette 64dp (avatar ring legible) + bottom scrim 84dp, ek hi layer-list view.
- Naam 1 line (13sp medium; unseen bold). Time sirf unseen contact card par.
- Seen/muted cards: alpha ki jagah ColorFilter se dim (offscreen layer nahi). Seen-state animation mein bg alpha anim hata di.
- "Add status" tile: apni photo full-bleed + bada + circle + "Add status" text; chhota avatar/ring nahi, avatar bind skip.
- Muted badge top-end (video badge ke neeche) kyunki top-start ab avatar ka hai.
- Skeleton card naye layout ke hisaab se.
- StoryReshareActivity: sirf stub ids use karta hai (unchanged) — visual check kar lena.
