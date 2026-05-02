package com.callx.app.adapters;

import android.content.Context;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.models.StatusItem;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * StatusListAdapter — Production-grade, section-aware status list.
 *
 * Sections (view types):
 *   TYPE_MY_STATUS     — always first; shows my own status ring or "Add" button
 *   TYPE_SECTION_HEADER — "Recent updates" / "Viewed updates"
 *   TYPE_CONTACT       — one row per contact with seen/unseen ring
 *
 * Uses DiffUtil for smooth, animated updates on real-time data changes.
 */
public class StatusListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ── View types ────────────────────────────────────────────────────────
    public static final int TYPE_MY_STATUS      = 0;
    public static final int TYPE_SECTION_HEADER = 1;
    public static final int TYPE_CONTACT        = 2;

    // ── Entry model ───────────────────────────────────────────────────────
    public static class Entry {
        public final String ownerUid;
        public final String ownerName;
        public final String ownerPhoto;
        public final Long   latestTimestamp;
        public final int    totalCount;
        public final int    unseenCount;
        public final StatusItem latestItem;

        public Entry(String ownerUid, String ownerName, String ownerPhoto,
                     Long latestTimestamp, int totalCount, int unseenCount,
                     StatusItem latestItem) {
            this.ownerUid        = ownerUid;
            this.ownerName       = ownerName;
            this.ownerPhoto      = ownerPhoto;
            this.latestTimestamp = latestTimestamp;
            this.totalCount      = totalCount;
            this.unseenCount     = unseenCount;
            this.latestItem      = latestItem;
        }
    }

    // ── Internal flat list items ──────────────────────────────────────────
    private static final int ITEM_MY    = 0;
    private static final int ITEM_HDR   = 1;
    private static final int ITEM_ROW   = 2;

    private static class FlatItem {
        int    kind;    // ITEM_*
        String header;  // ITEM_HDR
        Entry  entry;   // ITEM_ROW
        FlatItem(int k, String h, Entry e) { kind = k; header = h; entry = e; }
    }

    // ── State ─────────────────────────────────────────────────────────────
    private final String           myUid;
    private final List<StatusItem> myStatuses;
    private final Runnable         onMyStatusClick;
    private final Runnable         onAddStatusClick;
    private final ContactClickListener onContactClick;

    private List<FlatItem> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    public interface ContactClickListener {
        void onClick(String ownerUid, String ownerName);
    }

    public StatusListAdapter(String myUid,
                             List<StatusItem> myStatuses,
                             Runnable onMyStatusClick,
                             Runnable onAddStatusClick,
                             ContactClickListener onContactClick) {
        this.myUid           = myUid;
        this.myStatuses      = myStatuses;
        this.onMyStatusClick = onMyStatusClick;
        this.onAddStatusClick = onAddStatusClick;
        this.onContactClick  = onContactClick;
        setHasStableIds(false);
    }

    // ── Data update via DiffUtil ──────────────────────────────────────────

    public void update(List<Entry> unseen, List<Entry> seen) {
        List<FlatItem> next = new ArrayList<>();
        next.add(new FlatItem(ITEM_MY, null, null));
        if (!unseen.isEmpty()) {
            next.add(new FlatItem(ITEM_HDR, "Recent updates", null));
            for (Entry e : unseen) next.add(new FlatItem(ITEM_ROW, null, e));
        }
        if (!seen.isEmpty()) {
            next.add(new FlatItem(ITEM_HDR, "Viewed updates", null));
            for (Entry e : seen)   next.add(new FlatItem(ITEM_ROW, null, e));
        }

        final List<FlatItem> old = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                FlatItem o = old.get(oldPos), n = next.get(newPos);
                if (o.kind != n.kind) return false;
                if (o.kind == ITEM_HDR) return o.header.equals(n.header);
                if (o.kind == ITEM_ROW) return o.entry.ownerUid.equals(n.entry.ownerUid);
                return true; // MY_STATUS
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                FlatItem o = old.get(oldPos), n = next.get(newPos);
                if (o.kind == ITEM_ROW) {
                    return o.entry.unseenCount == n.entry.unseenCount
                        && Objects.equals(o.entry.latestTimestamp, n.entry.latestTimestamp)
                        && o.entry.totalCount == n.entry.totalCount;
                }
                if (o.kind == ITEM_MY) {
                    return myStatuses.size() == myStatuses.size(); // re-eval below
                }
                return true;
            }
        });
        items = next;
        diff.dispatchUpdatesTo(this);
    }

    // ── RecyclerView.Adapter ──────────────────────────────────────────────

    @Override public int getItemViewType(int pos) {
        int k = items.get(pos).kind;
        return k == ITEM_MY ? TYPE_MY_STATUS : k == ITEM_HDR ? TYPE_SECTION_HEADER : TYPE_CONTACT;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater li = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_MY_STATUS:
                return new MyStatusVH(li.inflate(R.layout.item_my_status, parent, false));
            case TYPE_SECTION_HEADER:
                return new HeaderVH(li.inflate(R.layout.item_status_header, parent, false));
            default:
                return new ContactVH(li.inflate(R.layout.item_status, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        FlatItem fi = items.get(pos);
        Context ctx = holder.itemView.getContext();
        if (holder instanceof MyStatusVH) bindMyStatus((MyStatusVH) holder, ctx);
        else if (holder instanceof HeaderVH) ((HeaderVH) holder).tvHeader.setText(fi.header);
        else bindContact((ContactVH) holder, fi.entry, ctx);
    }

    // ── My-Status row ─────────────────────────────────────────────────────

    private void bindMyStatus(MyStatusVH h, Context ctx) {
        if (myStatuses.isEmpty()) {
            h.ring.setVisibility(View.GONE);
            h.ivAdd.setVisibility(View.VISIBLE);
            h.tvName.setText("My Status");
            h.tvSub.setText("Tap to add status update");
            h.ivAvatar.setImageResource(R.drawable.ic_person);
            h.itemView.setOnClickListener(v -> onAddStatusClick.run());
        } else {
            StatusItem latest = myStatuses.get(myStatuses.size() - 1);
            h.ring.setVisibility(View.VISIBLE);
            h.ivAdd.setVisibility(View.GONE);
            h.tvName.setText("My Status");
            h.tvSub.setText(timeFmt.format(new Date(
                    latest.timestamp != null ? latest.timestamp : 0)));
            if (latest.ownerPhoto != null && !latest.ownerPhoto.isEmpty()) {
                Glide.with(ctx).load(latest.ownerPhoto)
                 .apply(RequestOptions.circleCropTransform())
                 .placeholder(R.drawable.ic_person).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
            // Thumbnail preview for image/video statuses
            if (h.ivThumb != null) {
                if (latest.thumbnailUrl != null || latest.mediaUrl != null) {
                    h.ivThumb.setVisibility(View.VISIBLE);
                    Glide.with(ctx)
                         .load(latest.thumbnailUrl != null
                               ? latest.thumbnailUrl : latest.mediaUrl)
                         .centerCrop()
                         .into(h.ivThumb);
                } else {
                    h.ivThumb.setVisibility(View.GONE);
                }
            }
            h.itemView.setOnClickListener(v -> onMyStatusClick.run());
        }
    }

    // ── Contact status row ────────────────────────────────────────────────

    private void bindContact(ContactVH h, Entry e, Context ctx) {
        h.tvName.setText(e.ownerName != null ? e.ownerName : "");
        h.tvTime.setText(e.latestTimestamp != null
                ? timeFmt.format(new Date(e.latestTimestamp)) : "");

        // Avatar
        if (e.ownerPhoto != null && !e.ownerPhoto.isEmpty()) {
            Glide.with(ctx).load(e.ownerPhoto)
                 .apply(RequestOptions.circleCropTransform())
                 .placeholder(R.drawable.ic_person).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // Seen / unseen ring
        h.ring.setBackgroundResource(e.unseenCount > 0
                ? R.drawable.circle_status_unseen
                : R.drawable.circle_status_seen);

        // Unseen badge count
        if (h.tvBadge != null) {
            if (e.unseenCount > 1) {
                h.tvBadge.setVisibility(View.VISIBLE);
                h.tvBadge.setText(String.valueOf(e.unseenCount));
            } else {
                h.tvBadge.setVisibility(View.GONE);
            }
        }

        // Sub-text: latest text / caption or media type hint
        if (h.tvSub != null) {
            StatusItem latest = e.latestItem;
            String sub = "";
            if (latest != null) {
                if ("image".equals(latest.type))       sub = "Photo";
                else if ("video".equals(latest.type))  sub = "Video";
                else if ("link".equals(latest.type))   sub = "Link";
                else if (latest.text != null)          sub = latest.text;
                if (latest.caption != null && !latest.caption.isEmpty()) {
                    sub = latest.caption;
                }
            }
            h.tvSub.setText(sub);
        }

        // Media thumbnail
        if (h.ivThumb != null) {
            StatusItem latest = e.latestItem;
            String thumbUrl = latest != null
                    ? (latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl)
                    : null;
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                h.ivThumb.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(thumbUrl).centerCrop().into(h.ivThumb);
            } else {
                h.ivThumb.setVisibility(View.GONE);
            }
        }

        h.itemView.setOnClickListener(v -> onContactClick.onClick(e.ownerUid, e.ownerName));
    }

    // ── ViewHolders ───────────────────────────────────────────────────────

    static class MyStatusVH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        ImageView       ivAdd, ring, ivThumb;
        TextView        tvName, tvSub;
        MyStatusVH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            ivAdd    = v.findViewById(R.id.iv_add);
            ring     = v.findViewById(R.id.ring);
            tvName   = v.findViewById(R.id.tv_name);
            tvSub    = v.findViewById(R.id.tv_sub);
            ivThumb  = v.findViewById(R.id.iv_thumb);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tv_header);
        }
    }

    static class ContactVH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        ImageView       ring, ivThumb;
        TextView        tvName, tvTime, tvSub, tvBadge;
        ContactVH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            ring     = v.findViewById(R.id.ring);
            tvName   = v.findViewById(R.id.tv_name);
            tvTime   = v.findViewById(R.id.tv_time);
            tvSub    = v.findViewById(R.id.tv_sub);
            tvBadge  = v.findViewById(R.id.tv_badge);
            ivThumb  = v.findViewById(R.id.iv_thumb);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────
    private static class Objects {
        static boolean equals(Object a, Object b) {
            return (a == null) ? (b == null) : a.equals(b);
        }
    }
}
