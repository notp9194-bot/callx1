package com.callx.app.conversation.info;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * MessageInfoAdapter — backs MessageInfoBottomSheet's RecyclerView.
 *
 * Rows are plain MessageInfoRow data built once by MessageInfoRowBuilder,
 * so onBindViewHolder is just text/image assignment on already-inflated,
 * recycled views. The old MessageInfoActivity inflated every row (and, for
 * a group's "Read by" section, fired a Glide load per member) synchronously
 * the moment the screen opened — for a large group that's dozens of
 * inflates + image loads in one go, all before anything was even visible.
 * Here only what's on screen gets inflated/bound, and rows scrolled past
 * get their view (and in-flight avatar load) recycled/cancelled.
 *
 * submitList() runs the new rows through DiffUtil instead of always calling
 * notifyDataSetChanged() — the list is built fresh once per open today, but
 * as soon as this sheet reacts to something live (e.g. a read-receipt
 * arriving while it's open), a full notifyDataSetChanged() would rebind
 * every visible row and drop scroll-position-sensitive state (like an
 * in-flight image fade) for no reason; DiffUtil only touches rows that
 * actually changed.
 */
public class MessageInfoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VT_PREVIEW = 0;
    public static final int VT_STATUS  = 1;
    public static final int VT_HEADER  = 2;
    public static final int VT_MEMBER  = 3;
    public static final int VT_EMPTY   = 4;

    /** Member avatars are decoded at exactly the size they're displayed at
     *  (38dp, matching item_message_info_member.xml) instead of full
     *  original resolution — smaller Bitmap, less Glide memory-cache
     *  pressure, especially with a large group's "Read by" list all
     *  potentially in cache at once. Public so callers that prefetch
     *  avatars ahead of the sheet opening (GroupChatActivity) can request
     *  the same size and land on the same Glide cache key. */
    public static final int AVATAR_SIZE_DP = 38;

    private final List<MessageInfoRow> rows = new ArrayList<>();

    public MessageInfoAdapter() {}

    /** Convenience for the initial bind. */
    public MessageInfoAdapter(List<MessageInfoRow> initialRows) {
        rows.addAll(initialRows);
    }

    /** Diffs newRows against the current list and dispatches only the
     *  actual inserts/removes/changes instead of rebinding everything. */
    public void submitList(List<MessageInfoRow> newRows) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new RowDiffCallback(rows, newRows));
        rows.clear();
        rows.addAll(newRows);
        result.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        switch (rows.get(position).type) {
            case PREVIEW: return VT_PREVIEW;
            case STATUS:  return VT_STATUS;
            case HEADER:  return VT_HEADER;
            case MEMBER:  return VT_MEMBER;
            default:      return VT_EMPTY;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VT_PREVIEW:
                return new PreviewVH(inf.inflate(R.layout.item_message_info_preview, parent, false));
            case VT_STATUS:
                return new StatusVH(inf.inflate(R.layout.item_message_info_status_row, parent, false));
            case VT_HEADER:
                return new HeaderVH(inf.inflate(R.layout.item_message_info_header, parent, false));
            default: // VT_MEMBER, VT_EMPTY share the same row layout
                return new MemberVH(inf.inflate(R.layout.item_message_info_member, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageInfoRow row = rows.get(position);
        switch (row.type) {
            case PREVIEW: ((PreviewVH) holder).bind(row); break;
            case STATUS:  ((StatusVH) holder).bind(row); break;
            case HEADER:  ((HeaderVH) holder).bind(row); break;
            case MEMBER:  ((MemberVH) holder).bind(row); break;
            case EMPTY:   ((MemberVH) holder).bindEmpty(row); break;
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        // Cancel any in-flight avatar load so a recycled row can't get a
        // stale image swapped in a frame after it's already been rebound.
        if (holder instanceof MemberVH) {
            Glide.with(((MemberVH) holder).ivAvatar.getContext()).clear(((MemberVH) holder).ivAvatar);
        }
    }

    private static int dpToPx(View v, int dp) {
        return (int) (dp * v.getResources().getDisplayMetrics().density);
    }

    // ── ViewHolders ───────────────────────────────────────────────────────

    static class PreviewVH extends RecyclerView.ViewHolder {
        final TextView tvPreview, tvSentTime;
        PreviewVH(View v) {
            super(v);
            tvPreview = v.findViewById(R.id.tv_preview);
            tvSentTime = v.findViewById(R.id.tv_sent_time);
        }
        void bind(MessageInfoRow row) {
            tvPreview.setText(row.previewLabel);
            tvSentTime.setText(row.sentTimeLabel);
        }
    }

    static class StatusVH extends RecyclerView.ViewHolder {
        final TextView tvLabel, tvTime;
        final ImageView ivIcon;
        StatusVH(View v) {
            super(v);
            tvLabel = v.findViewById(R.id.tv_status_label);
            tvTime = v.findViewById(R.id.tv_status_time);
            ivIcon = v.findViewById(R.id.iv_status_icon);
        }
        void bind(MessageInfoRow row) {
            tvLabel.setText(row.label);
            tvTime.setText(row.timeLabel);
            ivIcon.setImageResource(row.iconRes);
            ivIcon.setAlpha(row.dim ? 0.35f : 1f);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvHeader;
        HeaderVH(View v) {
            super(v);
            tvHeader = (TextView) v; // item_message_info_header.xml root IS the TextView
        }
        void bind(MessageInfoRow row) {
            tvHeader.setText(row.label);
        }
    }

    static class MemberVH extends RecyclerView.ViewHolder {
        final CircleImageView ivAvatar;
        final TextView tvName, tvTime;
        final ImageView ivTick;
        MemberVH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_member_avatar);
            tvName = v.findViewById(R.id.tv_member_name);
            tvTime = v.findViewById(R.id.tv_member_time);
            ivTick = v.findViewById(R.id.iv_member_tick);
        }
        void bind(MessageInfoRow row) {
            ivAvatar.setVisibility(View.VISIBLE);
            tvTime.setVisibility(View.VISIBLE);
            tvName.setText(row.label);
            tvName.setTextColor(tvName.getResources().getColor(R.color.text_primary));
            tvTime.setText(row.timeLabel);

            boolean showTick = row.iconRes != 0 && row.timeLabel != null && !row.timeLabel.isEmpty();
            ivTick.setVisibility(showTick ? View.VISIBLE : View.GONE);
            if (showTick) ivTick.setImageResource(row.iconRes);

            if (row.photoUrl != null && !row.photoUrl.isEmpty()) {
                int px = dpToPx(ivAvatar, AVATAR_SIZE_DP);
                Glide.with(ivAvatar.getContext())
                        .load(row.photoUrl)
                        .override(px, px)   // exact decode size — no full-res bitmap for a 38dp circle
                        .placeholder(R.drawable.ic_person)
                        .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }
        void bindEmpty(MessageInfoRow row) {
            ivAvatar.setVisibility(View.GONE);
            ivTick.setVisibility(View.GONE);
            tvTime.setVisibility(View.GONE);
            tvName.setText(row.label);
            tvName.setTextColor(tvName.getResources().getColor(R.color.text_muted));
        }
    }

    // ── Diffing ───────────────────────────────────────────────────────────

    private static class RowDiffCallback extends DiffUtil.Callback {
        private final List<MessageInfoRow> oldRows;
        private final List<MessageInfoRow> newRows;

        RowDiffCallback(List<MessageInfoRow> oldRows, List<MessageInfoRow> newRows) {
            this.oldRows = oldRows;
            this.newRows = newRows;
        }

        @Override public int getOldListSize() { return oldRows.size(); }
        @Override public int getNewListSize() { return newRows.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            MessageInfoRow a = oldRows.get(oldPos);
            MessageInfoRow b = newRows.get(newPos);
            if (a.type != b.type) return false;
            // Header text ("READ BY (3/5)") includes counts, so it doubles
            // as identity here; member/status rows are identified by their
            // label (name / "Seen" / "Delivered"), which is stable across
            // a single sheet's lifetime since MessageInfoData is rebuilt
            // fresh per open.
            return Objects.equals(a.label, b.label);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            return oldRows.get(oldPos).contentEquals(newRows.get(newPos));
        }
    }
}
