# UPGRADE NOTES v185 — Reels Upload 401 Fix

## Root cause

Reels video upload requests a Cloudinary "eager" transform
(`sp_full_hd/m3u8`) to build an HLS adaptive-streaming manifest.
That eager transform requires Cloudinary's **Adaptive Streaming
add-on**. On accounts where that add-on is not enabled, Cloudinary
does not skip the transform quietly — it rejects the **entire
signed upload request** with HTTP 401 Unauthorized, because the
add-on check happens at auth time.

This explains why only reels video upload failed with 401 while
photo uploads and chat media uploads (which never request `eager`)
kept working fine on the same Cloudinary account/keys.

## Fix

`VideoUploader.java`: the eager-HLS upload now automatically
retries once WITHOUT the eager parameter if the first attempt
comes back with `(401)`. The reel still uploads and plays
normally — it just won't have a pre-built HLS manifest unless
Adaptive Streaming is enabled on the Cloudinary account (the app
already had a fallback to plain quality URLs for this case, see
`onSuccessWithHls`).

## To get real HLS/ABR instead of the fallback

Enable the "Adaptive Streaming" add-on on the Cloudinary account
(Add-ons tab, Cloudinary dashboard) — free tier has a limited
quota. Once enabled, the eager request will succeed on the first
try and the retry path won't trigger.
