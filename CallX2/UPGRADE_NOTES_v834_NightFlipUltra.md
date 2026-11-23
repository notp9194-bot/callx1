# v834 — ultra-optimised live day/night re-theme (on top of v833)
1. ChatNightModeRefresher: no findViewById (v833 walked the whole tree incl. RecyclerView ~15x) -> uses ActivityChatBinding fields.
2. Colors resolved once per flip; views only touched when the value changes (text/hint colors) -> no redundant invalidate/requestLayout.
3. Input pill recolored in place (mutate + setColor/setStroke) instead of re-inflating bg_chat_input_capsule and re-setting the background.
4. ChatThemeController.applyScreenThemeForNightFlip(): skips applyWallpaper() (Glide request / ColorDrawable) — wallpaper is theme-independent.
5. IconTintCache warm-up for the OPPOSITE mode (ChatNightModeRefresher.prewarmOppositeTheme + ChatIconBarView.prewarmIconsFor): runs once per process, via idle handler -> IO thread, after the chat is up. The live flip then does cache hits only (same size as the live path; cache key ignores size).
6. Correctness guard for v829: MessageBubbleCanvasView.bind() identical-rebind early return now also requires `boundUiNight == current night bits`, so a holder that missed PAYLOAD_THEME (outside notified range / item-view cache) can't keep the old theme's bubble fill when text colors happen to match.
Not compiled offline — run CI.
