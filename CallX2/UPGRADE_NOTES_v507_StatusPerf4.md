# v507 — Status viewer perf (items 8 + 9)

## 8. Hardware layer during animation
`llViewerColumn` ko drag aur cube-turn ke dauraan `LAYER_TYPE_HARDWARE` milta hai, animation khatam hote hi `LAYER_TYPE_NONE`.
- `applyDrag()` -> `columnHwLayerOn()` (finger drag)
- `snapBackFromDrag()` end action -> `columnHwLayerOff()`
- `playUserSwitchTransition()` start -> On, end action -> Off
- `closeByDrag()` pe layer On rehta hai (activity finish ho rahi hai)

## 9. ViewStub (lazy inflate)
| Stub id | Inflated id | Layout file | Kab inflate hota hai |
|---|---|---|---|
| stub_text_status | fl_text_status | view_status_text_layer.xml | pehla text status |
| stub_reshare_card | fl_reshare_attribution_container | view_status_reshare_card.xml | pehla reshared status |
| stub_sticker_overlay | fl_sticker_overlay | view_status_sticker_overlay.xml | pehla sticker / load-error card |
| stub_quick_reaction | fl_quick_reaction_overlay | view_status_quick_reaction.xml | pehla swipe-up-to-react |

- Z-order same rehta hai (ViewStub apni jagah leta hai), sticker taps ab bhi touch_layer ke upar.
- Reshare stub pe `layout_gravity` + `marginBottom=100dp` hai (stub LayoutParams inflated root ko override karte hain).
- Sticker frame size ke liye fallback: overlay -> `flStoryCard` -> screen (pehli baar overlay measure nahi hota).
- Quick-reaction listeners inflate ke time ek baar wire hote hain (`ensureQuickReactionOverlay()`), `setupQuickReactionOverlay()` hata diya.
- Repost button logic ab reshare card ke inflate hone par depend nahi karta.
