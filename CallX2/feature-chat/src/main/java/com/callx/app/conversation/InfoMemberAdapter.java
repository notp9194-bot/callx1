package com.callx.app.conversation;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;
import com.google.android.material.imageview.ShapeableImageView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * InfoMemberAdapter — RecyclerView adapter for MessageInfoActivity.
 *
 * Group "Read By" aur "Delivered To" dono lists ke liye same adapter use hota hai.
 * Har row mein dikhata hai:
 *   • Member avatar (ShapeableImageView — circular)
 *   • Member name
 *   • Timestamp (dd MMM yyyy, hh:mm a format)
 *
 * Usage:
 *   List<InfoMemberAdapter.MemberRow> rows = new ArrayList<>();
 *   rows.add(new InfoMemberAdapter.MemberRow("Rahul", "https://...", 1700000001000L));
 *   rvReadBy.setAdapter(new InfoMemberAdapter(rows));
 */
public class InfoMemberAdapter extends RecyclerView.Adapter<InfoMemberAdapter.VH> {

    private final List<MemberRow> items;
    private final SimpleDateFormat dtFmt =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    public InfoMemberAdapter(List<MemberRow> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_info_member, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MemberRow row = items.get(pos);
        Context ctx = h.itemView.getContext();

        h.tvName.setText(row.name != null ? row.name : "Unknown");
        h.tvTime.setText(row.timestampMillis > 0
                ? dtFmt.format(new Date(row.timestampMillis))
                : "—");

        if (row.photoUrl != null && !row.photoUrl.isEmpty()) {
            Glide.with(ctx)
                    .load(row.photoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.circle_avatar_bg)
                    .error(R.drawable.circle_avatar_bg)
                    .circleCrop()
                    .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.circle_avatar_bg);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ShapeableImageView ivAvatar;
        TextView tvName;
        TextView tvTime;

        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_member_avatar);
            tvName   = v.findViewById(R.id.tv_member_name);
            tvTime   = v.findViewById(R.id.tv_member_time);
        }
    }

    /**
     * Data model for one row in Read By / Delivered To list.
     */
    public static class MemberRow {
        public final String name;
        public final String photoUrl;
        public final long   timestampMillis;

        public MemberRow(String name, String photoUrl, long timestampMillis) {
            this.name            = name;
            this.photoUrl        = photoUrl;
            this.timestampMillis = timestampMillis;
        }
    }
}
