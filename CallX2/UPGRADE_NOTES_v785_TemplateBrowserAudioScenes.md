# v785 — Template: Browser RecyclerView + skeleton, multi-scene counts, MP3->AAC

Done:
- 12 Skeleton: Browser/Saved grid shows pulsing placeholder cards while templates load.
- 13 RecyclerView: whole Browser is one RecyclerView (header | cards | footer), cards bound not rebuilt, DiffUtil updates.
- 17 (partial) TemplatePreviewDrawable is multi-scene aware: photoCount() = all scenes, sceneCount(), setScene(). Cards show correct photo count + scene badge.
- 18 TemplateAudioTranscoder: non-AAC clip audio (MP3/Opus/Vorbis/AMR...) is converted to AAC and muxed (was silent).
- TemplatePreviewDrawable also has sample mode (setSampleMode/setTimeMs/setBoxEffect) — API ready for 15/16, not wired in UI yet.

NOT done yet: 15 (Editor live filter/anim), 16 (Preview real preview + music), 17 (Editor multi-scene editing, Preview scene loop).
Not built/tested (no Gradle here). Build once and check Browser, Use flow, and a clip with MP3 audio.

Part 2 (15, 16):
- 15 Editor: stage shows a sample picture in every box; selected box plays its real filter + animation on a loop; changes (filter, animation, length) show at once.
- 16 Preview: boxes show sample pictures with real filters/animations, scenes play one after another (Scene n/N chip); music plays (soundUrl or sounds/{soundId}, from startMs), tap the music chip to mute. Templates hold no photos, so the picture is a sample.
- 17 Editor: multi-scene templates get a scene strip (Scene n · length); each scene has its own box tabs, filter, animation, duration and a scene-length slider. Total = sum of scenes. Saved into scenes[] + tdur.
