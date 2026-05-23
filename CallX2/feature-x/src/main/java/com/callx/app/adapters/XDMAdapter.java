package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.activities.XImageViewerActivity;
import com.callx.app.models.XDMMessage;
import com.callx.app.x.R;
import java.text.SimpleDateFormat;
import java.util.*;

public class XDMAdapter extends RecyclerView.Adapter<XDMAdapter.MsgVH> {

    private static final int VT_SENT = 0, VT_RECV = 1;
    private final Context ctx;
    private final String myUid;
    private final List<XDMMessage> msgs = new ArrayList<>();

    public XDMAdapter(Context ctx, String myUid) {
        this.ctx = ctx; this.myUid = myUid;
    }

    public void setMessages(List<XDMMessage> list) {
        msgs.clear(); msgs.addAll(list); notifyDataSetChanged();
    }

    @Override public int getItemViewType(int pos) {
        return myUid.equals(msgs.get(pos).senderUid) ? VT_SENT : VT_RECV;
    }

    @NonNull @Override
    public MsgVH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        int layout = vt == VT_SENT ? R.layout.item_x_dm_sent : R.layout.item_x_dm_recv;
        return new MsgVH(LayoutInflater.from(ctx).inflate(layout, p, false));
    }

    @Override public void onBindViewHolder(@NonNull MsgVH h, int pos) { h.bind(msgs.get(pos)); }
    @Override public int getItemCount() { return msgs.size(); }

    class MsgVH extends RecyclerView.ViewHolder {
        TextView tvText, tvTime, tvSeen;
        ImageView ivMedia;

        MsgVH(View v) {
            super(v);
            tvText  = v.findViewById(R.id.tv_dm_text);
            tvTime  = v.findViewById(R.id.tv_dm_time);
            tvSeen  = v.findViewById(R.id.tv_dm_seen);
            ivMedia = v.findViewById(R.id.iv_dm_media);
        }

        void bind(XDMMessage m) {
            if (tvText != null) {
                tvText.setVisibility(m.text != null && !m.text.isEmpty() ? View.VISIBLE : View.GONE);
                if (m.text != null) tvText.setText(m.text);
            }
            if (ivMedia != null) {
                if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                    ivMedia.setVisibility(View.VISIBLE);
                    Glide.with(ctx).load(m.mediaUrl).centerCrop().into(ivMedia);
                    ivMedia.setOnClickListener(v ->
                        ctx.startActivity(new Intent(ctx, XImageViewerActivity.class)
                            .putExtra("image_url", m.mediaUrl)));
                } else {
                    ivMedia.setVisibility(View.GONE);
                }
            }
            if (tvTime != null)
                tvTime.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.timestamp)));
            if (tvSeen != null) {
                // Show read receipt on sent messages
                tvSeen.setVisibility(myUid.equals(m.senderUid) ? View.VISIBLE : View.GONE);
                if (tvSeen.getVisibility() == View.VISIBLE)
                    tvSeen.setText(m.seen ? "Seen" : "Sent");
            }
        }
    }
}
