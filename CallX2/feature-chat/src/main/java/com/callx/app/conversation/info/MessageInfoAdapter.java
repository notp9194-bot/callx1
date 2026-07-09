package com.callx.app.conversation.info;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

import java.util.List;

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
 */
public class MessageInfoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VT_PREVIEW = 0;
    private static final int VT_STATUS  = 1;
    private static final int VT_HEADER  = 2;
    private static final int VT_MEMBER  = 3;
    private static final int VT_EMPTY   = 4;

    private final List<MessageInfoRow> rows;

    public MessageInfoAdapter(List<MessageInfoRow> rows) {
        this.rows = rows;
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
                Glide.with(ivAvatar.getContext())
                        .load(row.photoUrl)
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
}
