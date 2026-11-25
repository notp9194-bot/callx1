# v868 — Bubble Perf: bitmap-kind diagnostic (no behaviour change)

Checked the planned "media bitmaps -> HARDWARE" change: `BubbleBitmapPipeline` already decodes chat media as
`Bitmap.Config.HARDWARE` (API 28+, EXIF-normal, min side >= 128 px; software RGB_565/ARGB_8888 fallback). So no patch needed there.

Added to the Bubble Perf report: `bitmaps set on bubbles: hardware=N software=N (KB, max KB) thumbhash-placeholder=N`
(counted in setMediaBitmap / setMediaGroupBitmap only on real changes; zero cost when collection is off).
Many software bitmaps => GPU upload on first draw => candidate for the cmdIssue / gpu cost; if ~all are hardware, upload is not the cause.
