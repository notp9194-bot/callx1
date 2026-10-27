# v689 — Status editor: Guide caption-aware + "Fit" tool

Problem: editor ka "Guide" box viewer ka bottom chrome 96dp fixed maanta tha, jabki viewer ka `ll_bottom_chrome` caption ke saath badhta hai (tv_caption 17sp + 10dp, location, 32dp fade). Isliye guide ke andar rakha photo bhi viewer me neeche se chhup/"crop" ho jata tha. Guide sirf shade tha — photo ko andar rakhne ka koi tool nahi tha.

- `StoryFrameSpec.bottomChromeDp(captionLines, hasLocation)` — layout constants se (14 pad + 16 fade + caption 10 + 22/line, min 80dp). Unit test add.
- `StorySafeAreaView.setCaption()` — caption ko viewer ke caption width (screen - 28dp) pe StaticLayout se measure karta hai; `getSafeRect()` add.
- `NewStatusActivity`: etCaption TextWatcher → guide live update. Naya rail tool **"Fit"**: photo (current rotation ke saath) ko scale + center karke poora safe rect ke andar rakhta hai (blur backdrop bake me rehta hai).
- Reply bar check kiya: layout 8 + 44 + 12 = 64dp = `REPLY_BAR_DP` (sahi tha, badla nahi).
- NOT done: viewer me card ko chrome ke beech reserve karna (poore viewer layout ko chhoota hai: video/text/reshare card) — alag step.
