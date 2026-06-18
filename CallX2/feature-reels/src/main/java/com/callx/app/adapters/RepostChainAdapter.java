package com.callx.app.adapters;

import com.callx.app.reels.R;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.RepostModel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Timeline visualization of the viral repost chain.
 * Each item = one spread event with connector line above/below.
 */
public class RepostChainAdapter extends RecyclerView.Adapter<RepostChainAdapter.VH> {

    private final List<RepostModel> items;
    private final Context ctx;

    public RepostChainAdapter(List<RepostModel> items, Context ctx) {
        this.items = items;
        this.ctx   = ctx;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_repost_chain, p, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        RepostModel m = items.get(pos);

        h.tvPosition.setText("#" + (pos + 1));
        h.tvName.setText(m.reposterName != null ? m.reposterName : "Unknown");
        h.tvCaption.setText(m.caption != null && !m.caption.isEmpty()
                ? "\"" + m.caption + "\"" : "");
        h.tvCaption.setVisibility(m.caption != null && !m.caption.isEmpty()
                ? View.VISIBLE : View.GONE);

        String type = m.repostType != null ? m.repostType : "simple";
        h.tvType.setText("quote".equals(type) ? "💬 Quote" :
                         "story".equals(type) ? "📖 Story" : "🔁 Simple");

        String time = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(new Date(m.timestamp));
        h.tvTime.setText(time);

        // Hide connector line on last item
        h.viewConnector.setVisibility(pos == items.size() - 1 ? View.INVISIBLE : View.VISIBLE);

        if (m.reposterPhoto != null && !m.reposterPhoto.isEmpty()) {
            Glide.with(ctx).load(m.reposterPhoto).circleCrop().into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_default_avatar);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvPosition, tvName, tvCaption, tvTime, tvType;
        View viewConnector;

        VH(View v) {
            super(v);
            ivAvatar      = v.findViewById(R.id.iv_chain_avatar);
            tvPosition    = v.findViewById(R.id.tv_chain_position);
            tvName        = v.findViewById(R.id.tv_chain_name);
            tvCaption     = v.findViewById(R.id.tv_chain_caption);
            tvTime        = v.findViewById(R.id.tv_chain_time);
            tvType        = v.findViewById(R.id.tv_chain_type);
            viewConnector = v.findViewById(R.id.view_chain_connector);
        }
    }
}
