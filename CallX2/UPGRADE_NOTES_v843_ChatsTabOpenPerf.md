# v843 — Chats tab open: timing log + cold-start cuts
- NEW core/debug/TabOpenPerfLog + Chats-tab 3-dot menu item "🐞 Chats Open Log" (Copy/Clear). Marks (ms since process start): CallxApp.onCreate, MainActivity.onCreate, main inflate, first frame, ChatsFragment.onCreateView stages, first data, listeners attached, class preload done.
- CallxApp: `chats-class-preload` thread (MainActivity, ChatsFragment, ChatListAdapter, canvas row views, ViewPagerAdapter, ActivityMainBinding...) + warm chat_list_snapshot / chat_list_settings prefs off main.
- Keystore warmup order: AppLock -> ChatLock -> E2E.
- ChatsFragment: Firebase listeners (contacts/special/muted/groups) attached after first frame when a list is already shown (snapshot/in-memory); immediate only on empty first-ever open.
- baseline-prof.txt: ViewPagerAdapter + ChatsFragment/ChatListAdapter$VH/prewarm/canvas rules. Regenerate the profile on a release build.

## v844 (same zip lineage)
- More TabOpenPerfLog marks: app attachBaseContext, each CallxApp.onCreate main-thread step, onCreate end, MainActivity onStart/onResume start/end.
- CallxApp: checkAndRequestDomainVerification() (binder IPC, API 31+) moved off main, delayed 5 s.

## v845 — Voice-on-photo bubble: screenshot-2 layout (canvas path)
- Photo has NO overlay now. Opaque strip BELOW the photo: [play/pause circle | static waveform | m:ss | time+ticks]. Strip height is part of bubbleHeight (VOICE_STRIP_HEIGHT_DP=48), caption (if any) sits under the strip.
- Waveform = one cached Path (seeded from voiceUrl hash), drawn in local coords; played part = clipRect over the same Path. Animates ONLY while playing (setVoiceProgress from the existing 250 ms ticker); idle = zero per-frame work.
- Tap circle/waveform = play/pause; tap m:ss = speed cycle (speed shown only when != 1x). The old save-audio button is not in this layout (voiceDownloadRect empty).
- Video media keeps the old pill. Legacy View bubble (layout_msg_voice_on_image.xml) unchanged.

## v846 — voice strip: cheaper while playing + cheaper waveform
- While the clip plays, the strip is drawn LIVE in drawDynamicOverlayLayer() and left out of the cached bubble node (same scheme as v828 audio row). 250 ms ticks now invalidate only the strip rect — no full-bubble re-record. Play/pause flip still dirties the cache once.
- Waveform is a shared ALPHA_8 mask bitmap (LruCache ~190 KB, keyed seed+size), tinted at draw time: idle = 1 drawBitmap, played part = 1 clipped drawBitmap. No per-draw Path/round-rects.

## v847 — voice-on-photo: send + play latency
- SEND: voice E2E-encrypt + upload starts when the photo upload starts (startVoiceCaptionUploadEarly in startImageUpload, background thread) and runs in parallel. When the photo finishes, attachVoiceThenFinalize() finalizes ONCE (waits only if voice is still running). Voice failure => photo sent alone (as before). Failure/retry clears the state.
- RECEIVE: scheduleVoicePrefetch() — after a received voice-caption bubble stays bound 400 ms, background decrypt-key + MediaCache.get warms the clip; toggleAudio()'s existing cache-hit path then plays immediately.
- PLAY: old MediaPlayer stop()/release() moved off the tap's main-thread path (releasePlayerAsync). Full MediaPlayer reuse intentionally NOT done (GlobalVoicePlaybackManager holds the instance + ~10 release sites).

## v848 — voice-on-photo: smaller clip, no UI stall, policy-aware prefetch
- VoiceRecorder.setCompact(true) (only MediaEditActivity's voice-caption recorder): AAC mono 32 kbps / 22.05 kHz (~half the bytes). Default voice-message recorder unchanged.
- MediaEditActivity.onMicRelease: stopToFile() (MediaRecorder.stop + merge) now runs on AppBgExecutor; UI attach happens on the UI thread.
- Prefetch honours MediaAutoDownloadPolicy "audio" (default Wi-Fi only); retries later if the policy blocked it.
- Send: SHA-256 of the encrypted clip is computed while writing (DigestOutputStream) — no second read of the .enc file.
