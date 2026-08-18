# v213 — Status Music Sticker "Cannot Play This Audio" Fix

## Bug
Status music sticker (added from Reels' Trending Audio screen) opened the correct
`SoundDetailSheetFragment`/`SoundDetailFragment` bottom sheet with the right song
info — but tapping Play showed "Cannot play this audio".

## Root cause
`SoundDetailFragment`'s play button prefers a low-bitrate `previewAudioUrl` and
only falls back to the full-quality `audioUrl` (`soundUrl`) if no preview exists.

`loadSoundData()` only ever queried the Firebase `sounds/` node to fill in
`previewAudioUrl`. But tracks picked from Trending Audio's **Music tab** come from
a different node (`musicLibrary`, via `FirebaseUtils.getMusicLibraryRef()`) — so
for those tracks the `sounds/{soundId}` lookup never matched anything,
`previewAudioUrl` never resolved, and playback fell back to the raw `audioUrl`,
which ExoPlayer wasn't able to play reliably.

## Fix (`feature-reels/.../music/SoundDetailFragment.java`)
1. `loadSoundData()` now checks `snap.exists()` — if the sound isn't under
   `sounds/`, it calls a new `loadSoundDataFromMusicLibrary()` which fetches
   `audioUrl` / `previewAudioUrl` / `coverUrl` / `durationMs` from the
   `musicLibrary` node instead.
2. Added a safety net regardless of data source: if the preview URL fails to
   play twice, the player now automatically retries once more using the
   full-quality `soundUrl` (via new `skipPreviewUrl`/`triedFallbackUrl` flags)
   before showing the "Cannot play this audio" toast.

No changes needed in `feature-status` — the sticker → sheet wiring
(`StatusViewerActivity.openMusicStickerSoundSheet`, `StatusStickerPickerSheet`)
was already correct.
