package com.callx.app.adapters;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.activities.StatusViewerActivity;
import com.callx.app.models.StatusItem;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class StatusListAdapter extends RecyclerView.Adapter<StatusListAdapter.VH> {
    private final List<StatusItem> entries;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    public StatusListAdapter(List<StatusItem> entries) { this.entries = entries; }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_status, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        StatusItem s = entries.get(pos);
        Context ctx = h.itemView.getContext();
        h.tvName.setText(s.ownerName == null ? "Status" : s.ownerName);
        h.tvTime.setText(s.timestamp != null ? fmt.format(new Date(s.timestamp)) : "");
        if (s.ownerPhoto != null && !s.ownerPhoto.isEmpty()) {
            Glide.with(ctx).load(s.ownerPhoto).into(h.ivAvatar);
        } else h.ivAvatar.setImageResource(R.drawable.ic_person);
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(ctx, StatusViewerActivity.class);
            i.putExtra("ownerUid", s.ownerUid);
            ctx.startActivity(i);
        });
    }
    @Override public int getItemCount() { return entries.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvTime;
        CircleImageView ivAvatar;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvTime = v.findViewById(R.id.tv_time);
            ivAvatar = v.findViewById(R.id.iv_avatar);
        }
    }
}
