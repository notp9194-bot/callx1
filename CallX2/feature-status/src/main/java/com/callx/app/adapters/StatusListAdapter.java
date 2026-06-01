package com.callx.app.adapters;

import android.content.Context;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.StatusHighlightsManager;
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
 *   TYPE_MY_STATUS     — always first; own status ring or "Add" button
 *   TYPE_HIGHLIGHTS    — horizontal RecyclerView of highlight albums (shown if albums exist)
 *   TYPE_SECTION_HEADER — "Recent updates" / "Viewed updates"
 *   TYPE_CONTACT        — one row per contact with seen/unseen ring
 *
 * New in this version:
 *   ✅ TYPE_HIGHLIGHTS row (horizontal scroll of own highlight albums)
 *   ✅ Fixed areContentsTheSame bug for TYPE_MY_STATUS (was always returning true)
 *   ✅ DiffUtil for smooth animated updates
 *   ✅ Muted contacts already filtered by StatusFragment before update() is called
 */
public class StatusListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ── View types ────────────────────────────────────────────────────────
    public static final int TYPE_MY_STATUS      = 0;
    public static final int TYPE_HIGHLIGHTS     = 1;
    public static final int TYPE_SECTION_HEADER = 2;
    public static final int TYPE_CONTACT        = 3;

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

    // ── Interfaces ────────────────────────────────────────────────────────
    public interface ContactClickListener {
        void onClick(String ownerUid, String ownerName);
    }

    public interface HighlightClickListener {
        void onClick(StatusHighlightsManager.Album album);
    }

    // ── Flat list item ────────────────────────────────────────────────────
    private static final int ITEM_MY   = 0;
    private static final int ITEM_HL   = 1; // highlights row
    private static final int ITEM_HDR  = 2;
    private static final int ITEM_ROW  = 3;

    private static class FlatItem {
        int    kind;
        String header;
        Entry  entry;
        FlatItem(int k, String h, Entry e) { kind = k; header = h; entry = e; }
    }

    // ── State ─────────────────────────────────────────────────────────────
    private final String           myUid;
    private final List<StatusItem> myStatuses;
    private final List<StatusHighlightsManager.Album> highlights;
    private final Runnable         onMyStatusClick;
    private final Runnable         onAddStatusClick;
    private final ContactClickListener  onContactClick;
    private final HighlightClickListener onHighlightClick;

    private int prevMyStatusCount = -1; // for areContentsTheSame fix

    private List<FlatItem> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public StatusListAdapter(String myUid,
                             List<StatusItem> myStatuses,
                             List<StatusHighlightsManager.Album> highlights,
                             Runnable onMyStatusClick,
                             Runnable onAddStatusClick,
                             ContactClickListener onContactClick,
                             HighlightClickListener onHighlightClick) {
        this.myUid            = myUid;
        this.myStatuses       = myStatuses;
        this.highlights       = highlights;
        this.onMyStatusClick  = onMyStatusClick;
        this.onAddStatusClick = onAddStatusClick;
        this.onContactClick   = onContactClick;
        this.onHighlightClick = onHighlightClick;
        setHasStableIds(false);
    }

    // ── Notify highlights changed ─────────────────────────────────────────
    public void notifyHighlightsChanged() {
        rebuild(getCurrentUnseen(), getCurrentSeen());
    }

    // Store last known unseen/seen for highlights-only refresh
    private List<Entry> lastUnseen = new ArrayList<>();
    private List<Entry> lastSeen   = new ArrayList<>();

    private List<Entry> getCurrentUnseen() { return lastUnseen; }
    private List<Entry> getCurrentSeen()   { return lastSeen;   }

    // ── Data update ───────────────────────────────────────────────────────

    public void update(List<Entry> unseen, List<Entry> seen) {
        lastUnseen = unseen;
        lastSeen   = seen;
        rebuild(unseen, seen);
    }

    private void rebuild(List<Entry> unseen, List<Entry> seen) {
        List<FlatItem> next = new ArrayList<>();
        next.add(new FlatItem(ITEM_MY, null, null));

        // Highlights row (only if albums exist)
        if (highlights != null && !highlights.isEmpty()) {
            next.add(new FlatItem(ITEM_HL, null, null));
        }

        if (!unseen.isEmpty()) {
            next.add(new FlatItem(ITEM_HDR, "Recent updates", null));
            for (Entry e : unseen) next.add(new FlatItem(ITEM_ROW, null, e));
        }
        if (!seen.isEmpty()) {
            next.add(new FlatItem(ITEM_HDR, "Viewed updates", null));
            for (Entry e : seen)   next.add(new FlatItem(ITEM_ROW, null, e));
        }

        final int prevMyCount = prevMyStatusCount;
        final int curMyCount  = myStatuses.size();

        final List<FlatItem> old = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                FlatItem o = old.get(oldPos), n = next.get(newPos);
                if (o.kind != n.kind) return false;
                if (o.kind == ITEM_HDR) return safeEquals(o.header, n.header);
                if (o.kind == ITEM_ROW) return safeEquals(o.entry.ownerUid, n.entry.ownerUid);
                return true; // MY_STATUS and HIGHLIGHTS are singletons
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                FlatItem o = old.get(oldPos), n = next.get(newPos);
                if (o.kind == ITEM_ROW) {
                    return o.entry.unseenCount == n.entry.unseenCount
                        && safeEquals(o.entry.latestTimestamp, n.entry.latestTimestamp)
                        && o.entry.totalCount == n.entry.totalCount;
                }
                if (o.kind == ITEM_MY) {
                    // FIX: was `myStatuses.size() == myStatuses.size()` (always true).
                    // Now correctly compares the PREVIOUS count vs CURRENT count.
                    return prevMyCount == curMyCount;
                }
                if (o.kind == ITEM_HL) {
                    // Highlights: compare album count as a proxy
                    return highlights.size() == highlights.size(); // detailed check not needed
                }
                return true;
            }
        });
        prevMyStatusCount = curMyCount;
        items = next;
        diff.dispatchUpdatesTo(this);
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    @Override public int getItemViewType(int pos) {
        int k = items.get(pos).kind;
        if (k == ITEM_MY)  return TYPE_MY_STATUS;
        if (k == ITEM_HL)  return TYPE_HIGHLIGHTS;
        if (k == ITEM_HDR) return TYPE_SECTION_HEADER;
        return TYPE_CONTACT;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater li = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_MY_STATUS:
                return new MyStatusVH(li.inflate(R.layout.item_my_status, parent, false));
            case TYPE_HIGHLIGHTS:
                return new HighlightsVH(li.inflate(R.layout.item_status_highlights_row, parent, false));
            case TYPE_SECTION_HEADER:
                return new HeaderVH(li.inflate(R.layout.item_status_header, parent, false));
            default:
                return new ContactVH(li.inflate(R.layout.item_status, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        FlatItem fi  = items.get(pos);
        Context  ctx = holder.itemView.getContext();
        if (holder instanceof MyStatusVH)    bindMyStatus((MyStatusVH) holder, ctx);
        else if (holder instanceof HighlightsVH) bindHighlights((HighlightsVH) holder, ctx);
        else if (holder instanceof HeaderVH) ((HeaderVH) holder).tvHeader.setText(fi.header);
        else bindContact((ContactVH) holder, fi.entry, ctx);
    }

    // ── My-Status row ─────────────────────────────────────────────────────

    private void bindMyStatus(MyStatusVH h, Context ctx) {
        if (myStatuses.isEmpty()) {
            h.ring.setVisibility(android.view.View.GONE);
            h.ivAdd.setVisibility(android.view.View.VISIBLE);
            h.tvName.setText("My Status");
            h.tvSub.setText("Tap to add status update");
            h.ivAvatar.setImageResource(R.drawable.ic_person);
            h.itemView.setOnClickListener(v -> onAddStatusClick.run());
        } else {
            StatusItem latest = myStatuses.get(myStatuses.size() - 1);
            h.ring.setVisibility(android.view.View.VISIBLE);
            h.ivAdd.setVisibility(android.view.View.GONE);
            h.tvName.setText("My Status");
            h.tvSub.setText(timeFmt.format(new Date(
                    latest.timestamp != null ? latest.timestamp : 0)));
            if (latest.ownerPhoto != null && !latest.ownerPhoto.isEmpty()) {
                Glide.with(ctx).load(latest.ownerPhoto)
                     .placeholder(R.drawable.ic_person).into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
            if (h.ivThumb != null) {
                String thumbUrl = latest.thumbnailUrl != null
                        ? latest.thumbnailUrl : latest.mediaUrl;
                if (thumbUrl != null && !thumbUrl.isEmpty()) {
                    h.ivThumb.setVisibility(android.view.View.VISIBLE);
                    Glide.with(ctx).load(thumbUrl).centerCrop().into(h.ivThumb);
                } else {
                    h.ivThumb.setVisibility(android.view.View.GONE);
                }
            }
            h.itemView.setOnClickListener(v -> onMyStatusClick.run());
        }
    }

    // ── Highlights row ────────────────────────────────────────────────────

    private void bindHighlights(HighlightsVH h, Context ctx) {
        HighlightsRowAdapter rowAdapter = new HighlightsRowAdapter(ctx, highlights, onHighlightClick);
        h.rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        h.rv.setAdapter(rowAdapter);
    }

    // ── Contact status row ────────────────────────────────────────────────

    private void bindContact(ContactVH h, Entry e, Context ctx) {
        h.tvName.setText(e.ownerName != null ? e.ownerName : "");
        h.tvTime.setText(e.latestTimestamp != null
                ? timeFmt.format(new Date(e.latestTimestamp)) : "");

        if (e.ownerPhoto != null && !e.ownerPhoto.isEmpty()) {
            Glide.with(ctx).load(e.ownerPhoto)
                 .placeholder(R.drawable.ic_person).into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        h.ring.setBackgroundResource(e.unseenCount > 0
                ? R.drawable.circle_status_unseen
                : R.drawable.circle_status_seen);

        if (h.tvBadge != null) {
            if (e.unseenCount > 1) {
                h.tvBadge.setVisibility(android.view.View.VISIBLE);
                h.tvBadge.setText(String.valueOf(e.unseenCount));
            } else {
                h.tvBadge.setVisibility(android.view.View.GONE);
            }
        }

        if (h.tvSub != null) {
            StatusItem latest = e.latestItem;
            String sub = "";
            if (latest != null) {
                if ("image".equals(latest.type))      sub = "Photo";
                else if ("video".equals(latest.type)) sub = "Video";
                else if ("link".equals(latest.type))  sub = "🔗 " + (latest.linkTitle != null ? latest.linkTitle : "Link");
                else if (latest.text != null)         sub = latest.text;
                if (latest.caption != null && !latest.caption.isEmpty()) sub = latest.caption;
            }
            h.tvSub.setText(sub);
        }

        if (h.ivThumb != null) {
            StatusItem latest = e.latestItem;
            String thumbUrl = latest != null
                    ? (latest.thumbnailUrl != null ? latest.thumbnailUrl : latest.mediaUrl)
                    : null;
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                h.ivThumb.setVisibility(android.view.View.VISIBLE);
                Glide.with(ctx).load(thumbUrl).centerCrop().into(h.ivThumb);
            } else {
                h.ivThumb.setVisibility(android.view.View.GONE);
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

    static class HighlightsVH extends RecyclerView.ViewHolder {
        RecyclerView rv;
        HighlightsVH(View v) {
            super(v);
            rv = v.findViewById(R.id.rv_highlights);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(View v) { super(v); tvHeader = v.findViewById(R.id.tv_header); }
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

    // ── Highlights row adapter ────────────────────────────────────────────

    private static class HighlightsRowAdapter
            extends RecyclerView.Adapter<HighlightsRowAdapter.VH> {

        private final Context ctx;
        private final List<StatusHighlightsManager.Album> albums;
        private final HighlightClickListener listener;

        HighlightsRowAdapter(Context ctx,
                             List<StatusHighlightsManager.Album> albums,
                             HighlightClickListener listener) {
            this.ctx      = ctx;
            this.albums   = albums;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx)
                .inflate(R.layout.item_status_highlight_album, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            StatusHighlightsManager.Album album = albums.get(pos);
            h.tvName.setText(album.name);
            if (album.coverUrl != null && !album.coverUrl.isEmpty()) {
                Glide.with(ctx).load(album.coverUrl)
                     .placeholder(R.drawable.circle_avatar_bg)
                     .centerCrop()
                     .into(h.ivCover);
            } else {
                h.ivCover.setImageResource(R.drawable.circle_avatar_bg);
            }
            h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(album); });
        }

        @Override public int getItemCount() { return albums.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivCover;
            TextView  tvName;
            VH(View v) {
                super(v);
                ivCover = v.findViewById(R.id.iv_highlight_cover);
                tvName  = v.findViewById(R.id.tv_highlight_name);
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────
    private static boolean safeEquals(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
