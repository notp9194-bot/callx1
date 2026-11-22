# v817 — Swipe-reply bar inside the input capsule (mic-recording style)

Swipe-to-reply ab capsule ke **andar** dikhta hai: capsule upar ki taraf expand hota hai, upar reply card, neeche input row — bilkul mic recording bar jaisa.

## Changes (feature-chat)
- `activity_chat.xml`: `stub_reply_bar` (inflatedId `ll_reply_bar`) ab `cv_input_capsule` ke andar, margins stub par (8/8/8/2dp). Purana sibling stub hata diya.
- `layout_chat_reply_preview.xml`: outer container ka background/padding/margin hata (card hi dikhta hai), minHeight 48dp.
- `ChatInputBarContainer`: reply row measure/layout (top), input row / recording bar uske neeche. `setInputContentVisible()` reply row ko hide nahi karta (recording ke dauran reply upar dikhta rehta hai).
- `ReplyBarLayout`: `mergeCapsule()` hata diya; fade+rise show / fade+drop hide + 48dp close touch target same.
- `CapsuleRevealAnimator` (new): capsule height-reveal (clip-bounds spring) — 1:1 me pehle se tha, ab `GroupChatActivity` me bhi attach.
- Removed: `bg_chat_input_capsule_reply.xml`, `bg_chat_reply_container.xml`; `bg_chat_reply_card` radius 18 -> 16dp.

## No change needed
`ChatActivity` / `GroupChatActivity` reply call-sites (id `ll_reply_bar` same), `ReplyPreviewBinder`, `ReplyStateManager`, theme accent code.
