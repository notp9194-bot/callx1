# v383 — Chat in-app camera preview freeze fix (real root cause)

## Bug (user report)
Chat screen → input bar → camera icon → in-app camera opens, preview shows
live feed for a moment, then freezes on the last frame — before you even
tap to take a photo/video. Camera keeps running underneath (capture/record
still succeed), only the visible preview stalls.

## Why v382's fix didn't fully solve this
v382 fixed a *different* bug (SurfaceView/TextureView compositing desync)
in `ReelCameraActivity`/`MultiClipCameraActivity`, and noted
`ChatCameraActivity` already had that same fix. That part was true — but
`ChatCameraActivity` had a **second, independent** bug that only exists on
this screen.

## Real root cause
`ChatCameraActivity` was building `ImageCapture` with
`CAPTURE_MODE_ZERO_SHUTTER_LAG`, alongside `VideoCapture`, in the same
`bindToLifecycle()` call (this screen supports both photo and video from
one shutter button, unlike the Reel camera screens).

Android's CameraX docs are explicit: Zero-Shutter Lag is **not supported
together with VideoCapture** — it isn't a "requests it, silently falls
back if unsupported" flag in that specific combination. Asking for it
here made CameraX negotiate an invalid Preview + ZSL-ImageCapture +
VideoCapture capture-session configuration on every device. The camera2
session comes up half-broken from that: the preview stream stops getting
new frames (freezes on the last frame) while the session itself stays
alive — so capture/record keep working, matching exactly the reported
symptom.

## Fix
`feature-chat/.../ChatCameraActivity.java`: changed the capture mode from
`CAPTURE_MODE_ZERO_SHUTTER_LAG` to `CAPTURE_MODE_MINIMIZE_LATENCY` — the
same mode `ReelCameraActivity`/`MultiClipCameraActivity` already use,
which is fully supported alongside `VideoCapture`.

## Files touched
- `feature-chat/src/main/java/com/callx/app/conversation/controllers/ChatCameraActivity.java`

## Not touched
- The v382 SurfaceView→TextureView (`COMPATIBLE` mode) fix — still correct
  and still needed, left as-is.
- `ReelCameraActivity` / `MultiClipCameraActivity` — never used ZSL, so
  they were never affected by this bug.
