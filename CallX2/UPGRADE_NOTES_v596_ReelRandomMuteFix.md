# v596 — Reels random mute fix

Cause: on swipe, previous reel pauses (own-audible count -> 0) while AudioManager.isMusicActive()
is still true for a few hundred ms, so the next reel's decideAutoMuteForOtherAudio() treated it as
another app's audio and started muted.
Fix: ReelAudioPolicy remembers when own audio last stopped and never reports "other audio"
within 1.5s of it. Real other-app music (started earlier) is still detected.
