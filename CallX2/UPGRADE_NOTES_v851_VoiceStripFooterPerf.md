# v851 — Perf pass on voice strip + media footer strip

1. Same-clip rebind (scroll back over a voice photo): strip rect is parked in bindMedia() and restored
   in setVoiceCaption() instead of requestLayout() -> no extra measure pass per rebind
   (v849 fix kept correct, but cheaper).
2. Waveform mask: last mask kept per view by int key (seed/w/h) -> zero String allocs + no LRU lookup per draw.
3. Waveform seeds bucketed into 64 shared patterns + LRU 192KB -> 2.5MB (ALPHA_8): fast scroll over many
   voice photos reuses a handful of masks instead of building one per message on the main thread.
4. "elapsed + speed" label concat cached (changes ~1/s, was rebuilt on every ~250ms playback draw).
Unchanged: animation only while playing; photo has no overlay; footer strip is plain text draw.
Built without Gradle/SDK — run a normal build.
