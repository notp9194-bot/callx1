package com.callx.app.conversation.controllers;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.callx.app.chat.ui.ChatExportBottomSheet;
import com.callx.app.db.entity.MessageEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Export Chat — Chat 3-dot → "Export chat" → choose "Include media" / "Without
 * media" → builds a WhatsApp-style plain-text transcript of the conversation
 * and opens the system share sheet so the user can save it, send it, or
 * email it to themselves.
 *
 * "Include media" annotates each media message with its remote URL inline
 * (e.g. "[image omitted] https://...") since media here lives on Cloudinary
 * rather than as local files — there's nothing extra to zip up, but the
 * link still lets the recipient open it.
 */
public class ChatExportController {

    private static final SimpleDateFormat LINE_FMT =
            new SimpleDateFormat("dd/MM/yy, HH:mm", Locale.getDefault());
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    private final ChatActivityDelegate delegate;

    public ChatExportController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    /** Opens the "Include media / Without media" picker. */
    public void showExportSheet() {
        ChatExportBottomSheet.newInstance(this::exportChat)
                .show(delegate.getSupportFragmentManager(), ChatExportBottomSheet.TAG);
    }

    private void exportChat(boolean includeMedia) {
        String chatId = delegate.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            delegate.showToast("Nothing to export yet");
            return;
        }
        delegate.showToast("Preparing export…");

        delegate.getIoExecutor().execute(() -> {
            try {
                List<MessageEntity> messages = delegate.getDb().messageDao().getAllMessagesForExport(chatId);
                if (messages == null || messages.isEmpty()) {
                    delegate.runOnMain(() -> delegate.showToast("No messages to export"));
                    return;
                }

                File outFile = buildTranscriptFile(messages, includeMedia);
                delegate.runOnMain(() -> shareFile(outFile));
            } catch (Exception e) {
                delegate.runOnMain(() -> delegate.showToast("Export failed: " + e.getMessage()));
            }
        });
    }

    private File buildTranscriptFile(List<MessageEntity> messages, boolean includeMedia) throws Exception {
        String safePartner = sanitizeFileName(delegate.getPartnerName());
        File dir = new File(delegate.getActivity().getCacheDir(), "chat_export");
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, "WhatsApp Chat with " + safePartner + "_" + FILE_FMT.format(new java.util.Date()) + ".txt");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8")) {
            for (MessageEntity m : messages) {
                writer.write(buildLine(m, includeMedia));
                writer.write("\n");
            }
        }
        return outFile;
    }

    private String buildLine(MessageEntity m, boolean includeMedia) {
        String time = m.timestamp != null ? LINE_FMT.format(new java.util.Date(m.timestamp)) : "";
        String sender = m.senderId != null && m.senderId.equals(delegate.getCurrentUid())
                ? delegate.getCurrentName() : delegate.getPartnerName();
        if (sender == null) sender = "Unknown";

        String body = bodyFor(m, includeMedia);
        return "[" + time + "] " + sender + ": " + body;
    }

    private String bodyFor(MessageEntity m, boolean includeMedia) {
        if (Boolean.TRUE.equals(m.deleted)) return "This message was deleted";
        String type = m.type != null ? m.type : "text";

        switch (type) {
            case "image":
                return mediaTag("image", m.mediaUrl, includeMedia);
            case "video":
                return mediaTag("video", m.mediaUrl, includeMedia);
            case "audio":
                return mediaTag("audio", m.mediaUrl, includeMedia);
            case "gif":
                return mediaTag("GIF", m.mediaUrl, includeMedia);
            case "file":
                return mediaTag(m.fileName != null ? m.fileName : "file", m.mediaUrl, includeMedia);
            case "poll":
                return "[poll] " + (m.pollQuestion != null ? m.pollQuestion : "");
            case "multi_media":
                return "[media] " + (m.caption != null ? m.caption : "");
            case "contact":
                return "[contact] " + (m.contactName != null ? m.contactName : "")
                        + (m.contactPhone != null ? " " + m.contactPhone : "");
            case "location":
                return "[location] " + (m.locationAddress != null && !m.locationAddress.isEmpty()
                        ? m.locationAddress
                        : (m.locationLat != null
                                ? String.format(java.util.Locale.US, "%.5f,%.5f", m.locationLat, m.locationLng)
                                : ""));
            default:
                String text = m.text != null ? m.text : "";
                return Boolean.TRUE.equals(m.edited) ? text + " (edited)" : text;
        }
    }

    private String mediaTag(String label, String url, boolean includeMedia) {
        if (includeMedia && url != null && !url.isEmpty()) {
            return "[" + label + " omitted] " + url;
        }
        return "[" + label + " omitted]";
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "Chat";
        return name.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    delegate.getActivity(),
                    delegate.getActivity().getPackageName() + ".fileprovider",
                    file);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "CallX Chat Export");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            delegate.getActivity().startActivity(Intent.createChooser(shareIntent, "Export chat"));
        } catch (Exception e) {
            Toast.makeText(delegate.getActivity(), "Could not share file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
