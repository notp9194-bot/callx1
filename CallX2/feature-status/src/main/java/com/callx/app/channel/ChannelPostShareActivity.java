package com.callx.app.channel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import com.callx.app.status.R;
import com.google.android.material.button.MaterialButton;
import java.io.*;
import java.util.Date;

/**
 * ChannelPostShareActivity — share a channel post as a branded image card.
 *
 * Features:
 *   - Renders a styled post card preview (channel name, content, timestamp)
 *   - Save image to device gallery
 *   - Share as image via system share sheet
 *   - Copy post text to clipboard
 *   - Share as plain text
 */
public class ChannelPostShareActivity extends AppCompatActivity {

    public static final String EXTRA_POST_TEXT    = "postText";
    public static final String EXTRA_POST_TYPE    = "postType";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_POST_MEDIA_URL = "postMediaUrl";
    public static final String EXTRA_CHANNEL_ID   = "channelId";

    private View    cardSharePreview;
    private String  postText, postType, channelName, postMediaUrl, channelId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_post_share);

        postText    = getIntent().getStringExtra(EXTRA_POST_TEXT);
        postType    = getIntent().getStringExtra(EXTRA_POST_TYPE);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        postMediaUrl= getIntent().getStringExtra(EXTRA_POST_MEDIA_URL);
        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);

        Toolbar toolbar = findViewById(R.id.toolbar_post_share);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Share post");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        cardSharePreview = findViewById(R.id.card_share_preview);

        // Populate the preview card
        TextView tvShareChannelName = findViewById(R.id.tv_share_channel_name);
        TextView tvShareContent     = findViewById(R.id.tv_share_content);
        TextView tvShareTimestamp   = findViewById(R.id.tv_share_timestamp);
        TextView tvShareType        = findViewById(R.id.tv_share_type);

        if (tvShareChannelName != null)
            tvShareChannelName.setText(channelName != null ? channelName : "Channel");
        if (tvShareContent != null)
            tvShareContent.setText(buildContentLabel());
        if (tvShareTimestamp != null)
            tvShareTimestamp.setText(DateFormat.format("MMM dd, yyyy", new Date()).toString());
        if (tvShareType != null)
            tvShareType.setText(buildTypeIcon());

        // Action buttons
        MaterialButton btnSaveImage  = findViewById(R.id.btn_share_save_image);
        MaterialButton btnShareImage = findViewById(R.id.btn_share_as_image);
        MaterialButton btnCopyText   = findViewById(R.id.btn_share_copy_text);
        MaterialButton btnShareText  = findViewById(R.id.btn_share_as_text);

        if (btnSaveImage  != null) btnSaveImage.setOnClickListener(v  -> saveImageToGallery());
        if (btnShareImage != null) btnShareImage.setOnClickListener(v -> shareAsImage());
        if (btnCopyText   != null) btnCopyText.setOnClickListener(v   -> copyTextToClipboard());
        if (btnShareText  != null) btnShareText.setOnClickListener(v  -> shareAsText());
    }

    // ── Bitmap capture ────────────────────────────────────────────────────

    private Bitmap captureCardAsBitmap() {
        if (cardSharePreview == null) return null;
        cardSharePreview.setDrawingCacheEnabled(true);
        cardSharePreview.buildDrawingCache();
        Bitmap cache = cardSharePreview.getDrawingCache();
        if (cache == null) {
            // Manual draw fallback
            Bitmap bmp = Bitmap.createBitmap(
                    cardSharePreview.getWidth(), cardSharePreview.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            cardSharePreview.draw(canvas);
            return bmp;
        }
        Bitmap bmp = Bitmap.createBitmap(cache);
        cardSharePreview.setDrawingCacheEnabled(false);
        return bmp;
    }

    private void saveImageToGallery() {
        Bitmap bmp = captureCardAsBitmap();
        if (bmp == null) { Toast.makeText(this, "Could not capture card.", Toast.LENGTH_SHORT).show(); return; }

        String filename = "CallX_post_" + System.currentTimeMillis() + ".png";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/CallX");
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    Toast.makeText(this, "Image saved to gallery.", Toast.LENGTH_SHORT).show();
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "CallX");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(file)));
                Toast.makeText(this, "Image saved to gallery.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareAsImage() {
        Bitmap bmp = captureCardAsBitmap();
        if (bmp == null) { shareAsText(); return; }

        try {
            File cacheDir = new File(getCacheDir(), "shared_images");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File imgFile = new File(cacheDir, "callx_post_share.png");
            try (FileOutputStream fos = new FileOutputStream(imgFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            Uri imageUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", imgFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, imageUri);
            share.putExtra(Intent.EXTRA_TEXT, buildShareText());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share post as image"));
        } catch (Exception e) {
            shareAsText();
        }
    }

    private void copyTextToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String content = buildShareText();
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Channel post", content));
            Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareAsText() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, buildShareText());
        startActivity(Intent.createChooser(share, "Share post"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String buildShareText() {
        StringBuilder sb = new StringBuilder();
        if (channelName != null && !channelName.isEmpty())
            sb.append("[").append(channelName).append(" on CallX]\n");
        if (postText != null && !postText.isEmpty())
            sb.append(postText).append("\n");
        if (postMediaUrl != null && !postMediaUrl.isEmpty())
            sb.append(postMediaUrl);
        return sb.toString().trim();
    }

    private String buildContentLabel() {
        if (postText != null && !postText.isEmpty()) return postText;
        if ("image".equals(postType)) return "📷 Image";
        if ("audio".equals(postType)) return "🎵 Audio";
        if ("video".equals(postType)) return "🎬 Video";
        if ("poll".equals(postType))  return "📊 Poll";
        if ("link".equals(postType) && postMediaUrl != null) return postMediaUrl;
        if ("document".equals(postType)) return "📄 Document";
        return "";
    }

    private String buildTypeIcon() {
        if ("image".equals(postType))    return "📷";
        if ("audio".equals(postType))    return "🎵";
        if ("video".equals(postType))    return "🎬";
        if ("poll".equals(postType))     return "📊";
        if ("link".equals(postType))     return "🔗";
        if ("document".equals(postType)) return "📄";
        return "💬";
    }
}
