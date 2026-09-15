# v382 — Reel/Multi-clip camera preview freeze fix

## Bug
Reels camera (`ReelCameraActivity`) and multi-clip camera
(`MultiClipCameraActivity`) previews showed live feed for a second or two,
then visibly froze on the last frame — while CameraX kept running underneath
in the background (capture/record still worked, just the preview stopped
updating).

## Root cause
Same issue already diagnosed and fixed in `ChatCameraActivity` (see its
`onCreate` comment): `PreviewView` defaults to `PERFORMANCE` mode, which
backs the preview with a `SurfaceView`. A `SurfaceView` composites as its
own hardware layer outside the normal View drawing pass. Both these camera
screens overlay record/flip/flash/delete/done buttons and a ticking record
timer directly on top of the preview in the same layout — that repeated
sibling invalidation desyncs the SurfaceView's buffer swap on many GPU/OEM
skins, exactly the "dikhta hai, phir ruk jata hai" symptom.

`ChatCameraActivity` already had this fixed; `ReelCameraActivity` and
`MultiClipCameraActivity` never got the same fix.

## Fix
Added `previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE)`
right after each activity's `findViewById()` for its `PreviewView`, forcing
a `TextureView`-backed preview instead — draws in-order with sibling views,
so overlaid buttons/timer can't desync it.

## Files touched
- `feature-reels/src/main/java/com/callx/app/camera/ReelCameraActivity.java`
- `feature-reels/src/main/java/com/callx/app/camera/MultiClipCameraActivity.java`

## Not touched
- `ChatCameraActivity` — already had this fix.
- `PreviewView.getBitmap()` (used in `ReelCameraActivity` for thumbnail
  capture) works the same under COMPATIBLE mode, so no other code needed to
  change.
