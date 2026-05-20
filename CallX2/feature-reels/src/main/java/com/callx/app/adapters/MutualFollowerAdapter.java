package com.callx.app.adapters;

import android.content.Context;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.models.User;

import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Adapter for showing mutual followers list in MutualFollowersActivity.
 * Each item shows user avatar (loaded from thumbUrl/photoUrl) + name.
 */
public class MutualFollowerAdapter extends RecyclerView.Adapter<MutualFollowerAdapter.VH> {

    private final Context   ctx;
    private final List<User> list;

    public MutualFollowerAdapter(Context ctx, List<User> list) {
        this.ctx  = ctx;
        this.list = list;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_mutual_follower, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        User u = list.get(position);
        h.tvName.setText(u.name != null ? u.name : "Unknown");

        // Use thumbUrl first (100x100 WebP), fall back to photoUrl
        String avatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(ctx)
                 .load(avatarUrl)
                 .placeholder(R.drawable.ic_person)
                 .error(R.drawable.ic_person)
                 .circleCrop()
                 .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }
    }

    @Override public int getItemCount() { return list.size(); }

    static class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView        tvName;
        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            tvName   = v.findViewById(R.id.tv_name);
        }
    }
}
