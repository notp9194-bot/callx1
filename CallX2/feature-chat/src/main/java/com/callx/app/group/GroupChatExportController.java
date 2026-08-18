package com.callx.app.group;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import com.callx.app.chat.ui.ChatExportBottomSheet;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * GroupChatExportController — Export group chat to a text or HTML file.
 *
 * Features (superset of ChatExportController for 1:1 chats):
 *  - Text export (.txt) — WhatsApp-style transcript
 *  - HTML export (.html) — formatted, colour-coded, with sender names + timestamps
 *  - "Include media" flag — appends Cloudinary URLs inline
 *  - Uses ChatExportBottomSheet for "With / Without media" picker
 *  - Shares via Android system share-sheet (saves, email, send to cloud)
 *
 * Firebase backup path:
 *  Not directly to Firebase (messages are big). Instead we generate a local
 *  file and the user can send it wherever they like. A future cloud-backup
 *  feature would upload the file to Firebase Storage — hook added below.
 */
public class GroupChatExportController {

    private static final SimpleDateFormat LINE_FMT =
            new SimpleDateFormat("dd/MM/yy, HH:mm", Locale.getDefault());
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    /** Minimal interface needed — lets the controller work with any Activity host. */
    public interface Host {
        AppDatabase getDb();
        Executor getIoExecutor();
        void runOnUiThread(Runnable r);
        android.app.Activity getActivity();
        androidx.fragment.app.FragmentManager getSupportFragmentManager();
        String getGroupId();
        String getGroupName();
        String getCurrentUid();
        String getCurrentName();
    }

    private final Host host;

    public GroupChatExportController(Host host) {
        this.host = host;
    }

    /** Opens the picker bottom sheet (text-only vs. include media). */
    public void showExportSheet() {
        ChatExportBottomSheet.newInstance(this::startExport)
                .show(host.getSupportFragmentManager(), ChatExportBottomSheet.TAG);
    }

    private void startExport(boolean includeMedia) {
        host.runOnUiThread(() -> Toast.makeText(host.getActivity(), "Preparing export…", Toast.LENGTH_SHORT).show());
        host.getIoExecutor().execute(() -> {
            try {
                List<MessageEntity> msgs = host.getDb().messageDao()
                        .getAllMessagesForExport(host.getGroupId());
                if (msgs == null || msgs.isEmpty()) {
                    host.runOnUiThread(() -> Toast.makeText(host.getActivity(), "No messages to export", Toast.LENGTH_SHORT).show());
                    return;
                }
                File txt  = buildTxtFile(msgs, includeMedia);
                File html = buildHtmlFile(msgs, includeMedia);
                host.runOnUiThread(() -> showFormatPicker(txt, html));
            } catch (Exception e) {
                host.runOnUiThread(() -> Toast.makeText(host.getActivity(), "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showFormatPicker(File txt, File html) {
        String[] opts = {"📄 Plain text (.txt)", "🌐 HTML (formatted, .html)"};
        new androidx.appcompat.app.AlertDialog.Builder(host.getActivity())
                .setTitle("Choose format")
                .setItems(opts, (d, which) -> shareFile(which == 0 ? txt : html, which == 0 ? "text/plain" : "text/html"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── TXT builder ───────────────────────────────────────────────────────

    private File buildTxtFile(List<MessageEntity> msgs, boolean media) throws Exception {
        File dir = exportDir();
        File out = new File(dir, safeGroupName() + "_" + FILE_FMT.format(new Date()) + ".txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            w.write("CallX Group Chat Export — " + host.getGroupName() + "\n");
            w.write("Exported: " + new java.util.Date() + "\n");
            w.write("Messages: " + msgs.size() + "\n\n");
            for (MessageEntity m : msgs) {
                w.write(buildLine(m, media));
                w.write("\n");
            }
        }
        return out;
    }

    private String buildLine(MessageEntity m, boolean media) {
        String time   = m.timestamp != null ? LINE_FMT.format(new Date(m.timestamp)) : "?";
        String sender = resolveSender(m);
        String body   = bodyFor(m, media);
        return "[" + time + "] " + sender + ": " + body;
    }

    // ── HTML builder ──────────────────────────────────────────────────────

    private File buildHtmlFile(List<MessageEntity> msgs, boolean media) throws Exception {
        File dir = exportDir();
        File out = new File(dir, safeGroupName() + "_" + FILE_FMT.format(new Date()) + ".html");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            w.write("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
            w.write("<title>CallX — " + esc(host.getGroupName()) + "</title>");
            w.write("<style>body{font-family:sans-serif;background:#ECE5DD;margin:0;padding:16px}");
            w.write(".msg{display:flex;margin:4px 0}.sent{justify-content:flex-end}.recv{justify-content:flex-start}");
            w.write(".bubble{max-width:70%;padding:8px 12px;border-radius:12px;font-size:14px}");
            w.write(".sent .bubble{background:#DCF8C6}.recv .bubble{background:#fff}");
            w.write(".meta{font-size:11px;color:#888;margin-top:4px}");
            w.write(".sender{font-size:12px;color:#128C7E;font-weight:bold;margin-bottom:2px}");
            w.write(".date-sep{text-align:center;color:#777;font-size:12px;margin:12px 0}");
            w.write("</style></head><body>");
            w.write("<h2 style='color:#075E54'>📱 " + esc(host.getGroupName()) + "</h2>");
            w.write("<p style='color:#888'>Exported " + new Date() + " · " + msgs.size() + " messages</p>");
            w.write("<hr>");

            String lastDate = "";
            for (MessageEntity m : msgs) {
                String dateStr = m.timestamp != null
                        ? new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(new Date(m.timestamp)) : "";
                if (!dateStr.equals(lastDate)) {
                    w.write("<div class='date-sep'>" + esc(dateStr) + "</div>");
                    lastDate = dateStr;
                }
                boolean isMine = host.getCurrentUid().equals(m.senderId);
                String sender  = resolveSender(m);
                String time    = m.timestamp != null
                        ? new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(m.timestamp)) : "";
                String body    = bodyFor(m, media);

                w.write("<div class='msg " + (isMine ? "sent" : "recv") + "'><div class='bubble'>");
                if (!isMine) w.write("<div class='sender'>" + esc(sender) + "</div>");
                w.write("<div>" + esc(body) + "</div>");
                w.write("<div class='meta'>" + esc(time) + "</div>");
                w.write("</div></div>\n");
            }
            w.write("</body></html>");
        }
        return out;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private String resolveSender(MessageEntity m) {
        if (m.senderId != null && m.senderId.equals(host.getCurrentUid()))
            return host.getCurrentName() != null ? host.getCurrentName() : "You";
        if (Boolean.TRUE.equals(Boolean.TRUE.equals(m.isAnonymous))) return "Anonymous";
        return m.senderName != null ? m.senderName : "Member";
    }

    private String bodyFor(MessageEntity m, boolean media) {
        if (Boolean.TRUE.equals(m.deleted)) return "This message was deleted";
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "image":  return mediaTag("image", m.mediaUrl, media);
            case "video":  return mediaTag("video", m.mediaUrl, media);
            case "audio":  return mediaTag("audio", m.mediaUrl, media);
            case "gif":    return mediaTag("GIF",   m.mediaUrl, media);
            case "sticker":return mediaTag("Sticker", m.mediaUrl, media);
            case "file":   return mediaTag(m.fileName != null ? m.fileName : "file", m.mediaUrl, media);
            case "poll":   return "[poll] " + (m.pollQuestion != null ? m.pollQuestion : "");
            case "contact":return "[contact] " + (m.contactName != null ? m.contactName : "")
                    + (m.contactPhone != null ? " " + m.contactPhone : "");
            case "location":return "[location] " + (m.locationAddress != null ? m.locationAddress : "");
            default:
                String t = m.text != null ? m.text : "";
                return Boolean.TRUE.equals(Boolean.TRUE.equals(m.isAnonymous)) ? "🎭 " + t : t;
        }
    }

    private String mediaTag(String label, String url, boolean media) {
        if (media && url != null && !url.isEmpty()) return "[" + label + " omitted] " + url;
        return "[" + label + " omitted]";
    }

    private String safeGroupName() {
        String n = host.getGroupName();
        if (n == null) return "group";
        return n.replaceAll("[^a-zA-Z0-9 _-]", "").trim().replace(" ", "_");
    }

    private File exportDir() {
        File d = new File(host.getActivity().getCacheDir(), "group_export");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void shareFile(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    host.getActivity(),
                    host.getActivity().getPackageName() + ".fileprovider",
                    file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "CallX Group Chat — " + host.getGroupName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            host.getActivity().startActivity(Intent.createChooser(intent, "Export group chat"));
        } catch (Exception e) {
            Toast.makeText(host.getActivity(), "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
