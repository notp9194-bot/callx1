package com.callx.app.conversation.info;

/**
 * MessageInfoRow — one flattened row for MessageInfoAdapter's RecyclerView.
 *
 * MessageInfoRowBuilder turns a MessageInfoData into a List<MessageInfoRow>
 * up front as plain data (no LayoutInflater, no Glide). The old
 * MessageInfoActivity built the same rows by directly inflating +
 * populating a View per row into a LinearLayout — this splits that in two
 * so the adapter only has to bind/recycle whatever's actually on screen.
 */
public class MessageInfoRow {

    public enum Type { PREVIEW, STATUS, HEADER, MEMBER, EMPTY }

    public final Type type;

    // PREVIEW
    public String previewLabel;
    public String sentTimeLabel;

    // STATUS / MEMBER (shared fields)
    /** Status label ("Seen"/"Delivered") or member display name. */
    public String label;
    /** Formatted timestamp, or "" when not yet available. */
    public String timeLabel;
    /** Tick drawable res id; 0 = none. */
    public int iconRes;
    /** STATUS only — dims the icon when the state hasn't happened yet. */
    public boolean dim;
    /** MEMBER only. */
    public String photoUrl;

    private MessageInfoRow(Type type) {
        this.type = type;
    }

    public static MessageInfoRow preview(String previewLabel, String sentTimeLabel) {
        MessageInfoRow r = new MessageInfoRow(Type.PREVIEW);
        r.previewLabel = previewLabel;
        r.sentTimeLabel = sentTimeLabel;
        return r;
    }

    public static MessageInfoRow status(String label, String timeLabel, int iconRes, boolean dim) {
        MessageInfoRow r = new MessageInfoRow(Type.STATUS);
        r.label = label;
        r.timeLabel = timeLabel;
        r.iconRes = iconRes;
        r.dim = dim;
        return r;
    }

    public static MessageInfoRow header(String text) {
        MessageInfoRow r = new MessageInfoRow(Type.HEADER);
        r.label = text;
        return r;
    }

    public static MessageInfoRow member(String name, String timeLabel, int iconRes, String photoUrl) {
        MessageInfoRow r = new MessageInfoRow(Type.MEMBER);
        r.label = name;
        r.timeLabel = timeLabel;
        r.iconRes = iconRes;
        r.photoUrl = photoUrl;
        return r;
    }

    public static MessageInfoRow empty(String text) {
        MessageInfoRow r = new MessageInfoRow(Type.EMPTY);
        r.label = text;
        return r;
    }
}
