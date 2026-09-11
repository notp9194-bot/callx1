package com.callx.app.conversation;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;

import com.callx.app.chat.R;
import com.callx.app.utils.AppBgExecutor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * In-app reader for .txt attachments, opened when a "send as .txt file"
 * message is tapped in chat.
 *
 * Before this existed, tapping a .txt bubble routed straight to
 * ACTION_VIEW against an external app (or silently did nothing when the
 * FileProvider authority didn't match the manifest — see the fix in
 * {@link MessagePagingAdapter#onFileOpenClick()}). WhatsApp-level means
 * the common case — "what did I paste?" — never has to leave the app:
 *
 *  - Reads the file off the main thread and shows a spinner while it does
 *  - Content is selectable/scrollable, with a char/line count subtitle
 *  - Very large files are safely truncated with a visible notice instead
 *    of freezing the UI or OOMing on a pathological paste
 *  - Toolbar overflow still offers Copy all / Share (the real file, via
 *    FileProvider) / Open with (external app), so nothing WhatsApp/
 *    Telegram-style users expect is missing
 */
public class TextFileViewerActivity extends AppCompatActivity {

    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_FILE_NAME = "fileName";

    // Hard cap so a pathological multi-MB paste can't lock up the UI
    // thread rendering a single giant TextView.
    private static final int MAX_CHARS = 300_000;

    private Toolbar toolbar;
    private View progress;
    private View layoutError;
    private TextView tvError;
    private NestedScrollView scrollContent;
    private TextView tvContent;
    private TextView tvTruncatedNotice;

    private Uri fileUri;
    private String fileName;
    private String loadedText;

    public static void start(Context ctx, Uri fileUri, String fileName) {
        Intent i = new Intent(ctx, TextFileViewerActivity.class);
        i.putExtra(EXTRA_URI, fileUri);
        i.putExtra(EXTRA_FILE_NAME, fileName);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(ctx instanceof AppCompatActivity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_file_viewer);

        fileUri = getIntent().getParcelableExtra(EXTRA_URI);
        fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (fileName == null) fileName = "Text file";
        if (fileUri == null) { finish(); return; }

        toolbar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);
        layoutError = findViewById(R.id.layout_error);
        tvError = findViewById(R.id.tv_error);
        scrollContent = findViewById(R.id.scroll_content);
        tvContent = findViewById(R.id.tv_content);
        tvTruncatedNotice = findViewById(R.id.tv_truncated_notice);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(fileName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.btn_retry).setOnClickListener(v -> loadFile());

        loadFile();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_text_file_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_copy_all) {
            copyAll();
            return true;
        } else if (id == R.id.action_share) {
            shareFile();
            return true;
        } else if (id == R.id.action_open_with) {
            openWithExternalApp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadFile() {
        progress.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
        scrollContent.setVisibility(View.GONE);
        tvTruncatedNotice.setVisibility(View.GONE);

        final Uri uriToRead = fileUri;
        AppBgExecutor.execute(() -> {
            String text;
            boolean truncated = false;
            int lineCount = 0;
            try (InputStream is = getContentResolver().openInputStream(uriToRead)) {
                if (is == null) throw new IOException("Unable to open stream");
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    char[] buf = new char[8192];
                    int read;
                    while ((read = reader.read(buf)) != -1) {
                        if (sb.length() + read > MAX_CHARS) {
                            int remaining = MAX_CHARS - sb.length();
                            if (remaining > 0) sb.append(buf, 0, remaining);
                            truncated = true;
                            break;
                        }
                        sb.append(buf, 0, read);
                    }
                }
                text = sb.toString();
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) == '\n') lineCount++;
                }
                if (!text.isEmpty() && !text.endsWith("\n")) lineCount++;
            } catch (Exception e) {
                postToMain(() -> showError());
                return;
            }
            final String finalText = text;
            final boolean finalTruncated = truncated;
            final int finalLineCount = lineCount;
            postToMain(() -> showContent(finalText, finalTruncated, finalLineCount));
        });
    }

    private void postToMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private void showContent(String text, boolean truncated, int lineCount) {
        if (isFinishing() || isDestroyed()) return;
        loadedText = text;
        progress.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        scrollContent.setVisibility(View.VISIBLE);
        tvContent.setText(text);
        tvTruncatedNotice.setVisibility(truncated ? View.VISIBLE : View.GONE);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(
                    formatCount(text.length()) + " characters · " + formatCount(lineCount) + " lines"
                            + (truncated ? " (partial)" : ""));
        }
    }

    private void showError() {
        if (isFinishing() || isDestroyed()) return;
        progress.setVisibility(View.GONE);
        scrollContent.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        tvError.setText("Couldn't open this file.");
    }

    private String formatCount(int n) {
        return String.format(java.util.Locale.getDefault(), "%,d", n);
    }

    private void copyAll() {
        if (loadedText == null) {
            Toast.makeText(this, "Still loading…", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(fileName, loadedText));
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile() {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, fileUri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share " + fileName));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void openWithExternalApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, "text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open " + fileName + " with"));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }
}
