# v106 — Window Background Overdraw Fix

`activity_chat.xml`'s root FrameLayout already has an opaque, full-screen
`android:background="@color/surface_chat_bg"`. That means the Activity
theme's `windowBackground` drawn underneath it by the system was never
actually visible — pure wasted full-screen overdraw, repainted every frame,
in both `ChatActivity` and `GroupChatActivity` (they share `activity_chat.xml`
via `ActivityChatBinding`).

Added `getWindow().setBackgroundDrawable(null)` right after `setContentView()`
in both activities. Zero behavior change (root background is opaque and
covers the full screen in every state — wallpaper on, wallpaper off, shimmer
loading, RecyclerView visible), pure overdraw reduction. This is the standard
fix Android's own "Debug GPU Overdraw" tooling recommends for exactly this
pattern.

Combined with v105's canvas RecycledViewPool fix, this rounds out the
low-risk, high-confidence half of the "ultra smooth" pass. Text-layout
precompute and poll/media-group bitmap caching remain deliberately excluded
— see v105 notes for why.
