package com.callx.app.adapters;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.activities.GroupChatActivity;
import com.callx.app.models.Group;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.List;
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
    private final List<Group> groups;
    public GroupAdapter(List<Group> groups) { this.groups = groups; }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_group, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Group g = groups.get(pos);
        Context ctx = h.itemView.getContext();
        h.tvName.setText(g.name == null ? "Group" : g.name);
        h.tvLast.setText(g.lastMessage == null ? "" : g.lastMessage);
        int n = g.members != null ? g.members.size() : 0;
        h.tvMembers.setText(n + " members");

        // Load group avatar if available, else show default icon
        if (h.ivAvatar != null) {
            if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
                Glide.with(ctx)
                    .load(g.iconUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_group)
                    .error(R.drawable.ic_group)
                    .into(h.ivAvatar);
                h.ivAvatar.setPadding(0, 0, 0, 0);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_group);
                int pad = (int)(ctx.getResources().getDisplayMetrics().density * 12);
                h.ivAvatar.setPadding(pad, pad, pad, pad);
            }
        }

        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(ctx, GroupChatActivity.class);
            i.putExtra("groupId", g.id);
            i.putExtra("groupName", g.name);
            ctx.startActivity(i);
        });
    }
    @Override public int getItemCount() { return groups.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvLast, tvMembers;
        CircleImageView ivAvatar;
        VH(View v) {
            super(v);
            tvName    = v.findViewById(R.id.tv_group_name);
            tvLast    = v.findViewById(R.id.tv_group_last);
            tvMembers = v.findViewById(R.id.tv_group_members);
            ivAvatar  = v.findViewById(R.id.iv_group_avatar);
        }
    }
}
