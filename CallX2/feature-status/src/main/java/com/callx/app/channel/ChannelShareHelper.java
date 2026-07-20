package com.callx.app.channel;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import com.callx.app.models.ChannelPost;

/**
 * ChannelShareHelper — helper for all channel post sharing actions (v5).
 *
 * Actions:
 *   - shareViaAndroid()    — standard Android share sheet (text + link)
 *   - shareToStatus()      — share channel post as user's own status story
 *   - shareToAnotherChannel() — forward post to another channel the user owns/admins
 *   - sharePostQr()        — share a direct QR code pointing to the post
 *   - shareChannelQr()     — share a QR code for the channel join link
 *   - copyPostLink()       — copy deep link to clipboard
 */
public class ChannelShareHelper {

    private static final String DEEP_LINK_BASE = "https://callx.app/channel/";

    /** Share a channel post via Android share sheet. */
    public static void shareViaAndroid(@NonNull Context ctx, @NonNull ChannelPost post,
                                        String channelName) {
        String text = buildShareText(post, channelName);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        ctx.startActivity(Intent.createChooser(share, "Share post"));
    }

    /** Copy the post's deep link to the system clipboard. */
    public static void copyPostLink(@NonNull Context ctx, @NonNull ChannelPost post) {
        String link = DEEP_LINK_BASE + post.channelId + "/post/" + post.id;
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Post link", link));
            android.widget.Toast.makeText(ctx, "Link copied!", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Copy the channel invite link or public URL to clipboard. */
    public static void copyChannelLink(@NonNull Context ctx, String channelId,
                                        String channelName, String inviteLink) {
        String link = (inviteLink != null && !inviteLink.isEmpty())
                ? inviteLink : (DEEP_LINK_BASE + channelId);
        String text = "Follow " + (channelName != null ? channelName : "this channel")
                + " on CallX: " + link;
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Channel link", text));
            android.widget.Toast.makeText(ctx, "Link copied!", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Share the channel's public URL or invite link via Android share sheet. */
    public static void shareChannelViaAndroid(@NonNull Context ctx, String channelId,
                                               String channelName, String inviteLink) {
        String link = (inviteLink != null && !inviteLink.isEmpty())
                ? inviteLink : (DEEP_LINK_BASE + channelId);
        String text = "Follow " + (channelName != null ? channelName : "this channel")
                + " on CallX: " + link;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        ctx.startActivity(Intent.createChooser(share, "Share channel"));
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static String buildShareText(ChannelPost post, String channelName) {
        String link = DEEP_LINK_BASE + post.channelId + "/post/" + post.id;
        String from = channelName != null ? " from " + channelName : "";
        switch (post.type != null ? post.type : "text") {
            case "image":     return "📷 Check out this photo" + from + ": " + link;
            case "video":     return "🎬 Check out this video" + from + ": " + link;
            case "poll":      return "📊 Vote in this poll" + from + ": " + link;
            case "audio":     return "🎵 Listen to this" + from + ": " + link;
            case "document":  return "📄 Check out this doc" + from + ": " + link;
            case "event":     return "📅 You're invited" + from + ": " + link;
            case "broadcast": return "📢 Announcement" + from + ": " + link;
            default:
                String body = post.text != null && !post.text.isEmpty()
                    ? (post.text.length() > 100 ? post.text.substring(0, 100) + "…" : post.text) : "";
                return body + "\n" + link;
        }
    }
}
