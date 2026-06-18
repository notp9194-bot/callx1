package com.callx.app.adapters;

import com.callx.app.R;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.RepostModel;
import com.callx.app.utils.ViralRepostBadgeHelper;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RepostListAdapter extends RecyclerView.Adapter<RepostListAdapter.VH> {

    private final List<RepostModel> items;
    private final Context ctx;

    public RepostListAdapter(List<RepostModel> items, Context ctx) {
        this.items = items;
        this.ctx   = ctx;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_repost, p, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        RepostModel m = items.get(pos);

        h.tvName.setText(m.reposterName != null ? m.reposterName : "");
        h.tvCaption.setText(m.caption != null && !m.caption.isEmpty() ? m.caption : "");
        h.tvCaption.setVisibility(m.caption != null && !m.caption.isEmpty()
                ? View.VISIBLE : View.GONE);

        // Repost type badge
        String type = m.repostType != null ? m.repostType : "simple";
        switch (type) {
            case "quote": h.tvTypeBadge.setText("💬 Quote"); break;
            case "story": h.tvTypeBadge.setText("📖 Story"); break;
            default:      h.tvTypeBadge.setText("🔁 Repost"); break;
        }

        // Timestamp
        String time = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(new Date(m.timestamp));
        h.tvTime.setText(time);

        // Avatar
        if (m.reposterPhoto != null && !m.reposterPhoto.isEmpty()) {
            Glide.with(ctx).load(m.reposterPhoto).circleCrop().into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_default_avatar);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvName, tvCaption, tvTime, tvTypeBadge;

        VH(View v) {
            super(v);
            ivAvatar    = v.findViewById(R.id.iv_reposter_avatar);
            tvName      = v.findViewById(R.id.tv_reposter_name);
            tvCaption   = v.findViewById(R.id.tv_repost_caption);
            tvTime      = v.findViewById(R.id.tv_repost_time);
            tvTypeBadge = v.findViewById(R.id.tv_repost_type_badge);
        }
    }
}
