# v623 — Adjust Music: real audio waveform (polish item 8)

Placeholder seeded-random bars ab asli loudness se replace hote hain.

- NEW `core/.../views/AudioWaveformExtractor.java`: MediaExtractor + MediaCodec se track decode, 160 time-buckets ka RMS,
  normalise (0.14..1). Single background thread, cancellable `Job`, disk cache `cacheDir/waveforms` (SHA-1 of url, max 60 files).
  Fail / >15 min / 60s timeout => placeholder bars hi rehte hain.
- `AudioTrimWaveformView`: `setRealAmplitudes(float[])` (bar count ke hisaab se resample, 350ms morph animation),
  rotation/resize par raw data se dobara resample.
- `ReelPhotoMusicTrimActivity` (Reels photo + Status) aur `ReelMusicTrimActivity`: audio prepare hone par decode start,
  "Change" / destroy par cancel.
