# v258 — ReelAudioMixerActivity: Pitch Shift Not Audible After Export (Fix)

## Bug
Pitch adjustment in `ReelAudioMixerActivity` was clearly audible during preview
but disappeared (or was barely noticeable) in the final uploaded reel.

## Root Cause
- **Preview** uses `MediaPlayer.setPlaybackParams(PlaybackParams.setPitch(...))`
  — Android's built-in, duration-preserving pitch shift. Correct pitch, correct
  speed. Sounded right.
- **Export** (`AudioMixHelper.doMixWithConfig()` → `pitchShiftPcm()`) used a
  naive resample-based trick: `resamplePcm(pcm, 44100 * 2^(sem/12), 44100)`.
  Resampling like this changes pitch **and** playback speed/duration together
  — it doesn't isolate pitch.
- To keep the shifted music track in sync with the video length, the caller
  then forcibly re-looped/trimmed the result back to `mic.length`. That
  re-loop/trim is a destructive length-fit, not a pitch-preserving operation —
  it chopped or repeated chunks of the already-wrong-duration track, washing
  out most of the pitch effect, especially at small semitone values.

## Fix
`AudioMixHelper.pitchShiftPcm()` now does a proper two-stage, duration-preserving
grain-based pitch shift:

1. **`timeStretchOla()`** — Hann-windowed overlap-add (OLA) granular
   resynthesis. Reads 2048-sample grains from the input at one hop rate and
   writes them (75% overlap, Hann crossfade) at a different fixed hop rate.
   This stretches/compresses the signal to `length × rate` **while preserving
   the original pitch**.
2. **`resampleToLength()`** — linear-interpolation resample of that
   pitch-preserved, stretched signal back down/up to the **exact original
   sample count**. Resampling a stretched signal back to the original length
   is what actually shifts the pitch by `rate` — net duration stays identical
   to the input the whole time.

Because the result is now guaranteed to match the input length exactly, the
destructive post-shift re-loop/trim in `doMixWithConfig()` was removed — it's
no longer needed and was the thing wiping the effect out.

## Files changed
- `feature-reels/src/main/java/com/callx/app/music/AudioMixHelper.java`
  - `pitchShiftPcm()` — rewritten (duration-preserving, two-stage)
  - `timeStretchOla()` — new (Hann-window OLA time-stretch)
  - `resampleToLength()` — new (exact-length linear resample)
  - `doMixWithConfig()` — removed now-redundant re-loop/trim after pitch shift
  - `mixTwoAudioFiles()` — comment updated (no functional change needed there)

## Verification
Ran the new algorithm standalone against a 440 Hz test tone at ±5 and ±12
semitones: output sample count matched input exactly in all cases, and the
estimated frequency shifted in the correct direction and magnitude (e.g.
+12 semitones → ~880 Hz, -12 semitones → ~220 Hz).
