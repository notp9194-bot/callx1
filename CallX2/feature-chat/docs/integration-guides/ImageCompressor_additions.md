// ═══════════════════════════════════════════════════════════════════
// ADD THIS METHOD TO ImageCompressor.java
// (if it doesn't already have a URI-returning compress method)
// ═══════════════════════════════════════════════════════════════════

/**
 * Compress an image from a content/file URI and return a new file URI.
 *
 * @param context   Application context
 * @param sourceUri Source image URI (content:// or file://)
 * @param quality   JPEG quality 0-100 (85 = good balance)
 * @param maxDim    Max width/height in pixels (1080 = HD quality)
 * @return URI to compressed file in cache dir, or null on failure
 *
 * Usage in MediaPreviewActivity:
 *   Uri compressed = ImageCompressor.compressToUri(ctx, uri, 85, 1080);
 */
public static Uri compressToUri(Context context, Uri sourceUri, int quality, int maxDim) {
    try {
        InputStream is = context.getContentResolver().openInputStream(sourceUri);
        if (is == null) return null;

        // Decode with sub-sampling to avoid OOM on large images
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, opts);
        is.close();

        int sampleSize = 1;
        while (opts.outWidth / sampleSize > maxDim * 2
                || opts.outHeight / sampleSize > maxDim * 2) {
            sampleSize *= 2;
        }

        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sampleSize;
        is = context.getContentResolver().openInputStream(sourceUri);
        if (is == null) return null;
        Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
        is.close();

        if (bitmap == null) return null;

        // Scale down if still larger than maxDim
        float scale = Math.min(
                (float) maxDim / bitmap.getWidth(),
                (float) maxDim / bitmap.getHeight()
        );
        if (scale < 1f) {
            int newW = Math.round(bitmap.getWidth() * scale);
            int newH = Math.round(bitmap.getHeight() * scale);
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
            bitmap.recycle();
            bitmap = scaled;
        }

        // Write to cache file
        File cacheDir = new File(context.getCacheDir(), "compressed_media");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File outFile = new File(cacheDir, "img_" + System.currentTimeMillis() + ".jpg");

        FileOutputStream fos = new FileOutputStream(outFile);
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
        fos.flush();
        fos.close();
        bitmap.recycle();

        return Uri.fromFile(outFile);

    } catch (Exception e) {
        android.util.Log.e("ImageCompressor", "compressToUri failed", e);
        return null;
    }
}

// Required imports to add at top of ImageCompressor.java:
// import android.content.Context;
// import android.graphics.Bitmap;
// import android.graphics.BitmapFactory;
// import android.net.Uri;
// import java.io.File;
// import java.io.FileOutputStream;
// import java.io.InputStream;
