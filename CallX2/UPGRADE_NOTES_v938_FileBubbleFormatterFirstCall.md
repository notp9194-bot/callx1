# v938 — file bubble first-bind spike
v937 report: `file` typeBind max 42.8 ms / 24.8 ms (n=2 per run), everything else < 1 ms. Cause: android.text.format.Formatter.formatShortFileSize
first call (ICU/resource init) in the canvas file branch. Replaced by the adapter's own formatFileSize() (already used by image/gif/sticker gates).
