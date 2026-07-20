package com.callx.app.group;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import com.callx.app.chat.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.google.firebase.auth.FirebaseAuth;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatBackupActivity — Standalone backup/export screen.
 *
 * Works for both 1:1 chats (chatId) and group chats (groupId).
 * Opened from the group/chat 3-dot menu → "Backup / Export".
 *
 * Features:
 *  1. Export as .txt (plain text transcript)
 *  2. Export as .html (formatted, colour-coded)
 *  3. Include/exclude media links toggle
 *  4. "Backup Now" → saves .txt to app's Documents dir
 *  5. "View Saved Backups" → lists previously saved files
 *
 * Zero external dependencies beyond Room + FileProvider.
 */
public class ChatBackupActivity extends AppCompatActivity {

    public static final String EXTRA_CHAT_ID   = "chatId";    // 1:1 chat
    public static final String EXTRA_GROUP_ID  = "groupId";   // group chat
    public static final String EXTRA_CHAT_NAME = "chatName";  // display name

    private static final SimpleDateFormat LINE_FMT =
            new SimpleDateFormat("dd/MM/yy, HH:mm", Locale.getDefault());
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    private String chatId, currentUid, currentName, chatName;
    private boolean isGroup = false;
    private SwitchCompat swMedia;
    private TextView tvLastBackup, tvBackupSize, tvBackupCount;
    private final Executor io = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_backup);

        groupId:
        {
            String gid = getIntent().getStringExtra(EXTRA_GROUP_ID);
            String cid = getIntent().getStringExtra(EXTRA_CHAT_ID);
            if (gid != null) { chatId = gid; isGroup = true; }
            else if (cid != null) { chatId = cid; isGroup = false; }
            else { finish(); return; }
        }
        chatName   = getIntent().getStringExtra(EXTRA_CHAT_NAME);
        if (chatName == null) chatName = isGroup ? "Group" : "Chat";

        if (FirebaseAuth.getInstance().getCurrentUser() == null) { finish(); return; }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String dn   = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
        currentName = dn != null ? dn : "Me";

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Backup · " + chatName);
        }

        tvLastBackup  = findViewById(R.id.tv_last_backup);
        tvBackupSize  = findViewById(R.id.tv_backup_size);
        tvBackupCount = findViewById(R.id.tv_backup_count);
        swMedia       = findViewById(R.id.sw_include_media);

        refreshBackupInfo();

        findViewById(R.id.row_export_txt).setOnClickListener(v -> export("txt"));
        findViewById(R.id.row_export_html).setOnClickListener(v -> export("html"));
        findViewById(R.id.row_backup_now).setOnClickListener(v -> backupNow());
        findViewById(R.id.row_view_backups).setOnClickListener(v -> viewSavedBackups());
    }

    // ── Export ────────────────────────────────────────────────────────────

    private void export(String format) {
        boolean includeMedia = swMedia.isChecked();
        Toast.makeText(this, "Preparing " + format.toUpperCase() + " export…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(this);
                List<MessageEntity> msgs = db.messageDao().getAllMessagesForExport(chatId);
                if (msgs == null || msgs.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "No messages to export", Toast.LENGTH_SHORT).show());
                    return;
                }
                File out = format.equals("html")
                        ? buildHtml(msgs, includeMedia) : buildTxt(msgs, includeMedia);
                runOnUiThread(() -> shareFile(out, format.equals("html") ? "text/html" : "text/plain"));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Export error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── Backup Now ────────────────────────────────────────────────────────

    private void backupNow() {
        ProgressBar pb = findViewById(R.id.pb_backup);
        pb.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(this);
                List<MessageEntity> msgs = db.messageDao().getAllMessagesForExport(chatId);
                File backupDir = new File(getExternalFilesDir(null), "CallX_Backups");
                if (!backupDir.exists()) backupDir.mkdirs();
                File out = new File(backupDir, safeName() + "_" + FILE_FMT.format(new Date()) + ".txt");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
                    w.write("CallX Backup — " + chatName + "\nDate: " + new Date() + "\n\n");
                    for (MessageEntity m : msgs) w.write(buildLine(m, true) + "\n");
                }
                String prefs = "backup_" + chatId;
                getSharedPreferences(prefs, MODE_PRIVATE).edit()
                        .putLong("last_backup_ts", System.currentTimeMillis())
                        .putString("last_backup_file", out.getAbsolutePath())
                        .apply();
                runOnUiThread(() -> {
                    pb.setVisibility(View.GONE);
                    refreshBackupInfo();
                    Toast.makeText(this, "Backup saved: " + out.getName(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pb.setVisibility(View.GONE);
                    Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void viewSavedBackups() {
        File backupDir = new File(getExternalFilesDir(null), "CallX_Backups");
        File[] files = backupDir.listFiles((d, n) -> n.startsWith(safeName()) && n.endsWith(".txt"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "No backups found. Tap 'Backup Now' first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++)
            names[i] = files[i].getName() + "  (" + humanSize(files[i].length()) + ")";
        final File[] fref = files;
        new AlertDialog.Builder(this)
                .setTitle("Saved Backups")
                .setItems(names, (d, w) -> shareFile(fref[w], "text/plain"))
                .setNegativeButton("Close", null).show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void refreshBackupInfo() {
        String prefs = "backup_" + chatId;
        long ts = getSharedPreferences(prefs, MODE_PRIVATE).getLong("last_backup_ts", 0);
        tvLastBackup.setText("Last backup: " + (ts > 0 ? LINE_FMT.format(new Date(ts)) : "Never"));
        File backupDir = new File(getExternalFilesDir(null), "CallX_Backups");
        File[] files = backupDir.listFiles((d, n) -> n.startsWith(safeName()));
        int count = files != null ? files.length : 0;
        long totalSize = 0;
        if (files != null) for (File f : files) totalSize += f.length();
        tvBackupSize.setText("Backup size: " + humanSize(totalSize));
        if (tvBackupCount != null)
            tvBackupCount.setText(count + " backup" + (count == 1 ? "" : "s") + " saved");
    }

    private File buildTxt(List<MessageEntity> msgs, boolean media) throws Exception {
        File dir = exportDir();
        File out = new File(dir, safeName() + "_" + FILE_FMT.format(new Date()) + ".txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            w.write("CallX Chat Export — " + chatName + "\nExported: " + new Date()
                    + "\nMessages: " + msgs.size() + "\n\n");
            for (MessageEntity m : msgs) { w.write(buildLine(m, media)); w.write("\n"); }
        }
        return out;
    }

    private File buildHtml(List<MessageEntity> msgs, boolean media) throws Exception {
        File dir = exportDir();
        File out = new File(dir, safeName() + "_" + FILE_FMT.format(new Date()) + ".html");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            w.write("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>" + esc(chatName) + "</title>"
                    + "<style>body{font-family:sans-serif;background:#ECE5DD;padding:16px}"
                    + ".msg{display:flex;margin:4px 0}.s{justify-content:flex-end}.r{justify-content:flex-start}"
                    + ".b{max-width:70%;padding:8px 12px;border-radius:12px;font-size:14px}"
                    + ".s .b{background:#DCF8C6}.r .b{background:#fff}"
                    + ".m{font-size:11px;color:#888;margin-top:4px}"
                    + ".sn{font-size:12px;color:#128C7E;font-weight:bold;margin-bottom:2px}"
                    + ".ds{text-align:center;color:#777;font-size:12px;margin:12px 0}</style></head><body>");
            w.write("<h2 style='color:#075E54'>📱 " + esc(chatName) + "</h2>");
            w.write("<p style='color:#888'>Exported " + new Date() + " · " + msgs.size() + " messages</p><hr>");
            String lastDate = "";
            for (MessageEntity m : msgs) {
                String dateStr = m.timestamp != null ? new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(new Date(m.timestamp)) : "";
                if (!dateStr.equals(lastDate)) { w.write("<div class='ds'>" + esc(dateStr) + "</div>"); lastDate = dateStr; }
                boolean mine = currentUid.equals(m.senderId);
                String sender = mine ? currentName : (m.senderName != null ? m.senderName : "Member");
                if (Boolean.TRUE.equals(Boolean.TRUE.equals(m.isAnonymous))) sender = "Anonymous";
                String time = m.timestamp != null ? new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(m.timestamp)) : "";
                String body = bodyFor(m, media);
                w.write("<div class='msg " + (mine ? "s" : "r") + "'><div class='b'>");
                if (!mine) w.write("<div class='sn'>" + esc(sender) + "</div>");
                w.write("<div>" + esc(body) + "</div><div class='m'>" + esc(time) + "</div></div></div>\n");
            }
            w.write("</body></html>");
        }
        return out;
    }

    private String buildLine(MessageEntity m, boolean media) {
        String time   = m.timestamp != null ? LINE_FMT.format(new Date(m.timestamp)) : "?";
        boolean mine  = currentUid.equals(m.senderId);
        String sender = mine ? currentName : (m.senderName != null ? m.senderName : "Member");
        if (Boolean.TRUE.equals(Boolean.TRUE.equals(m.isAnonymous))) sender = "Anonymous";
        return "[" + time + "] " + sender + ": " + bodyFor(m, media);
    }

    private String bodyFor(MessageEntity m, boolean media) {
        if (Boolean.TRUE.equals(m.deleted)) return "This message was deleted";
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "image":   return mt("image", m.mediaUrl, media);
            case "video":   return mt("video", m.mediaUrl, media);
            case "audio":   return mt("audio", m.mediaUrl, media);
            case "gif":     return mt("GIF",   m.mediaUrl, media);
            case "sticker": return mt("Sticker", m.mediaUrl, media);
            case "file":    return mt(m.fileName != null ? m.fileName : "file", m.mediaUrl, media);
            case "poll":    return "[poll] " + (m.pollQuestion != null ? m.pollQuestion : "");
            case "contact": return "[contact] " + (m.contactName != null ? m.contactName : "");
            case "location":return "[location] " + (m.locationAddress != null ? m.locationAddress : "");
            default:        return m.text != null ? m.text : "";
        }
    }

    private String mt(String l, String url, boolean m) {
        return m && url != null && !url.isEmpty() ? "[" + l + " omitted] " + url : "[" + l + " omitted]";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private String safeName() {
        if (chatName == null) return "chat";
        return chatName.replaceAll("[^a-zA-Z0-9 _-]", "").trim().replace(" ", "_");
    }

    private File exportDir() {
        File d = new File(getCacheDir(), "chat_export"); if (!d.exists()) d.mkdirs(); return d;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private void shareFile(File file, String mime) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime); i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, "CallX Chat — " + chatName);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Export chat"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
