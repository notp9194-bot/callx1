package com.callx.app.adapters;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.*;
import androidx.recyclerview.widget.ListAdapter;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusItem;
import java.util.*;

/**
 * StatusListAdapter — RecyclerView adapter for StatusFragment.
 *
 * Sections / view types:
 *   TYPE_MY_STATUS  (0) — Large row at top, "My status" + last thumb + add overlay
 *   TYPE_HEADER     (1) — Section header: "Recent updates" / "Viewed updates"
 *   TYPE_CONTACT    (2) — Contact row: ring (green=unseen/grey=seen), avatar, name, time, badge, thumb
 *
 * DiffUtil for smooth animations on any list change.
 * Long-press on contact row → mute/unmute options.
 */
public class StatusListAdapter
        extends ListAdapter<StatusListAdapter.ListItem, RecyclerView.ViewHolder> {

    // ── View types ─────────────────────────────────────────────────────────
    public static final int TYPE_MY_STATUS = 0;
    public static final int TYPE_HEADER    = 1;
    public static final int TYPE_CONTACT   = 2;

    private final Context   ctx;
    private final Callbacks callbacks;

    // ── DiffUtil ───────────────────────────────────────────────────────────
    private static final DiffUtil.ItemCallback<ListItem> DIFF_CB =
        new DiffUtil.ItemCallback<ListItem>() {
            @Override public boolean areItemsTheSame(@NonNull ListItem a, @NonNull ListItem b) {
                if (a.type != b.type) return false;
                if (a.type == TYPE_HEADER)    return Objects.equals(a.headerText, b.headerText);
                if (a.type == TYPE_MY_STATUS) return true;
                if (a.type == TYPE_CONTACT)   return Objects.equals(a.contactRow.uid, b.contactRow.uid);
                return false;
            }
            @Override public boolean areContentsTheSame(@NonNull ListItem a, @NonNull ListItem b) {
                if (a.type == TYPE_HEADER) return Objects.equals(a.headerText, b.headerText);
                if (a.type == TYPE_MY_STATUS) {
                    return Objects.equals(a.myStatuses, b.myStatuses);
                }
                if (a.type == TYPE_CONTACT) {
                    ContactRow ra = a.contactRow, rb = b.contactRow;
                    return ra.unseenCount == rb.unseenCount
                        && ra.hasUnseen == rb.hasUnseen
                        && ra.newestTimestamp == rb.newestTimestamp
                        && Objects.equals(ra.items, rb.items);
                }
                return false;
            }
        };

    public StatusListAdapter(Context ctx, Callbacks callbacks) {
        super(DIFF_CB);
        this.ctx       = ctx;
        this.callbacks = callbacks;
    }

    @Override public int getItemViewType(int pos) { return getItem(pos).type; }

    // ── ViewHolder creation ───────────────────────────────────────────────
    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(ctx);
        switch (viewType) {
            case TYPE_MY_STATUS:
                return new MyStatusVH(inf.inflate(
                    resId("item_my_status"), parent, false));
            case TYPE_HEADER:
                return new HeaderVH(inf.inflate(
                    resId("item_status_header"), parent, false));
            default: // TYPE_CONTACT
                return new ContactVH(inf.inflate(
                    resId("item_status"), parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        ListItem item = getItem(pos);
        if (holder instanceof MyStatusVH) bindMyStatus((MyStatusVH) holder, item.myStatuses);
        else if (holder instanceof HeaderVH) bindHeader((HeaderVH) holder, item.headerText);
        else if (holder instanceof ContactVH) bindContact((ContactVH) holder, item.contactRow);
    }

    // ── My Status ─────────────────────────────────────────────────────────
    private void bindMyStatus(MyStatusVH vh, List<StatusItem> myStatuses) {
        if (myStatuses == null || myStatuses.isEmpty()) {
            if (vh.tvStatusTime   != null) vh.tvStatusTime.setText("Tap to add status update");
            if (vh.ivThumb        != null) vh.ivThumb.setVisibility(View.GONE);
            if (vh.ivAddOverlay   != null) vh.ivAddOverlay.setVisibility(View.VISIBLE);
        } else {
            int count = myStatuses.size();
            StatusItem latest = myStatuses.get(0);
            if (vh.tvStatusTime != null)
                vh.tvStatusTime.setText(formatTime(latest.timestamp)
                    + (count > 1 ? " • " + count + " updates" : ""));
            if (vh.ivThumb != null && latest.thumbnailUrl != null && !latest.thumbnailUrl.isEmpty()) {
                vh.ivThumb.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(latest.thumbnailUrl).centerCrop().into(vh.ivThumb);
            }
            if (vh.ivAddOverlay != null) vh.ivAddOverlay.setVisibility(View.VISIBLE);
        }

        vh.root.setOnClickListener(v -> callbacks.onMyStatusClick());
        vh.root.setOnLongClickListener(v -> { callbacks.onMyStatusLongPress(); return true; });
    }

    // ── Header ────────────────────────────────────────────────────────────
    private void bindHeader(HeaderVH vh, String text) {
        if (vh.tvHeader != null) vh.tvHeader.setText(text);
    }

    // ── Contact ───────────────────────────────────────────────────────────
    private void bindContact(ContactVH vh, ContactRow row) {
        if (vh.tvName   != null) vh.tvName.setText(row.name);
        if (vh.tvTime   != null) vh.tvTime.setText(formatTime(row.newestTimestamp));

        // Ring color: green = unseen, grey = seen
        if (vh.ringView != null) {
            int ringColor = row.hasUnseen
                ? Color.parseColor("#25D366")
                : Color.parseColor("#B0B0B0");
            vh.ringView.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ringColor));
        }

        // Unseen badge
        if (vh.tvBadge != null) {
            if (row.hasUnseen && row.unseenCount > 0) {
                vh.tvBadge.setVisibility(View.VISIBLE);
                vh.tvBadge.setText(row.unseenCount > 9 ? "9+" : String.valueOf(row.unseenCount));
            } else {
                vh.tvBadge.setVisibility(View.GONE);
            }
        }

        // Caption preview
        if (vh.tvCaption != null && !row.items.isEmpty()) {
            StatusItem latest = row.items.get(row.items.size() - 1);
            String preview = captionPreview(latest);
            vh.tvCaption.setText(preview);
        }

        // Thumbnail
        if (vh.ivThumb != null && !row.items.isEmpty()) {
            StatusItem latest = row.items.get(row.items.size() - 1);
            if (latest.thumbnailUrl != null && !latest.thumbnailUrl.isEmpty()) {
                vh.ivThumb.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(latest.thumbnailUrl).centerCrop().into(vh.ivThumb);
            } else {
                vh.ivThumb.setVisibility(View.GONE);
            }
        }

        // Avatar
        if (vh.ivAvatar != null) {
            Glide.with(ctx)
                .load(row.photoUrl)
                .placeholder(android.R.drawable.ic_menu_report_image)
                .circleCrop()
                .into(vh.ivAvatar);
        }

        vh.root.setOnClickListener(v ->
            callbacks.onContactStatusClick(row.uid, row.items));

        vh.root.setOnLongClickListener(v -> {
            showContactOptions(row);
            return true;
        });
    }

    private void showContactOptions(ContactRow row) {
        String muteLabel = StatusMuteManagerRef.isMuted(ctx, row.uid) ? "Unmute" : "Mute";
        String[] opts = {muteLabel + " " + row.name, "Report"};
        new android.app.AlertDialog.Builder(ctx)
            .setItems(opts, (d, w) -> {
                if (w == 0) {
                    boolean nowMuting = !StatusMuteManagerRef.isMuted(ctx, row.uid);
                    callbacks.onMuteToggle(row.uid, nowMuting);
                }
            }).show();
    }

    // ── ViewHolders ────────────────────────────────────────────────────────
    static class MyStatusVH extends RecyclerView.ViewHolder {
        View     root, ivAddOverlay, ringView;
        ImageView ivAvatar, ivThumb;
        TextView tvName, tvStatusTime;
        MyStatusVH(View v) {
            super(v); root = v;
            ivAvatar    = v.findViewById(resId2(v, "iv_my_avatar"));
            ivThumb     = v.findViewById(resId2(v, "iv_my_thumb"));
            ivAddOverlay= v.findViewById(resId2(v, "iv_add_overlay"));
            tvName      = v.findViewById(resId2(v, "tv_my_name"));
            tvStatusTime= v.findViewById(resId2(v, "tv_my_status_time"));
            ringView    = v.findViewById(resId2(v, "view_ring"));
        }
        static int resId2(View v, String n) {
            return v.getContext().getResources()
                .getIdentifier(n, "id", v.getContext().getPackageName());
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(View v) {
            super(v);
            tvHeader = v.findViewById(
                v.getContext().getResources().getIdentifier(
                    "tv_section_header", "id", v.getContext().getPackageName()));
        }
    }

    static class ContactVH extends RecyclerView.ViewHolder {
        View     root, ringView;
        ImageView ivAvatar, ivThumb;
        TextView tvName, tvTime, tvCaption, tvBadge;
        ContactVH(View v) {
            super(v); root = v;
            ivAvatar  = v.findViewById(resId(v, "iv_contact_avatar"));
            ivThumb   = v.findViewById(resId(v, "iv_status_thumb"));
            tvName    = v.findViewById(resId(v, "tv_contact_name"));
            tvTime    = v.findViewById(resId(v, "tv_status_time"));
            tvCaption = v.findViewById(resId(v, "tv_status_caption_preview"));
            tvBadge   = v.findViewById(resId(v, "tv_unseen_badge"));
            ringView  = v.findViewById(resId(v, "view_status_ring"));
        }
        static int resId(View v, String n) {
            return v.getContext().getResources()
                .getIdentifier(n, "id", v.getContext().getPackageName());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private String captionPreview(StatusItem item) {
        if (item == null) return "";
        if ("text".equals(item.type) && item.text != null) {
            return item.text.length() > 40 ? item.text.substring(0, 40) + "…" : item.text;
        }
        if ("image".equals(item.type))  return "📷 Photo";
        if ("video".equals(item.type))  return "🎥 Video";
        if ("gif".equals(item.type))    return "GIF";
        if ("poll".equals(item.type))   return "📊 " + (item.pollQuestion != null ? item.pollQuestion : "Poll");
        if ("link".equals(item.type))   return "🔗 " + (item.linkDomain != null ? item.linkDomain : "Link");
        return item.text != null ? item.text : "";
    }

    private String formatTime(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        long mins = diff / 60000;
        if (mins < 1)  return "just now";
        if (mins < 60) return mins + "m ago";
        long hrs = mins / 60;
        if (hrs < 24)  return hrs + "h ago";
        return android.text.format.DateFormat.format("MMM d", new java.util.Date(ts)).toString();
    }

    private int resId(String name) {
        return ctx.getResources().getIdentifier(name, "layout", ctx.getPackageName());
    }

    // Tiny bridge to avoid direct dependency in ViewHolder
    private static class StatusMuteManagerRef {
        static boolean isMuted(Context c, String uid) {
            return com.callx.app.utils.StatusMuteManager.get(c).isMuted(uid);
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────
    public static class ListItem {
        public final int               type;
        public final String            headerText;
        public final List<StatusItem>  myStatuses;
        public final ContactRow        contactRow;

        public ListItem(int type, Object data) {
            this.type = type;
            if (type == TYPE_HEADER)    { headerText = (String) data; myStatuses = null; contactRow = null; }
            else if (type == TYPE_MY_STATUS) { myStatuses = (List<StatusItem>) data; headerText = null; contactRow = null; }
            else                        { contactRow = (ContactRow) data; headerText = null; myStatuses = null; }
        }
    }

    public static class ContactRow {
        public final String           uid;
        public final String           name;
        public final String           photoUrl;
        public final List<StatusItem> items;
        public final boolean          hasUnseen;
        public final int              unseenCount;
        public final long             newestTimestamp;

        public ContactRow(String uid, String name, String photoUrl,
                          List<StatusItem> items, boolean hasUnseen,
                          int unseenCount, long newestTimestamp) {
            this.uid            = uid;
            this.name           = name;
            this.photoUrl       = photoUrl;
            this.items          = items;
            this.hasUnseen      = hasUnseen;
            this.unseenCount    = unseenCount;
            this.newestTimestamp = newestTimestamp;
        }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────
    public interface Callbacks {
        void onMyStatusClick();
        void onMyStatusLongPress();
        void onContactStatusClick(String contactUid, List<StatusItem> items);
        void onMuteToggle(String contactUid, boolean mute);
    }
}
