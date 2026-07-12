package com.callx.app.channels;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.status.R;

import java.util.List;
import java.util.function.Consumer;

/** Row adapter for the "Explore channels" bottom sheet — lists every channel with a
 *  Follow / Following toggle (no dismiss action, unlike the suggestions row). */
public class ChannelsExploreAdapter extends RecyclerView.Adapter<ChannelsExploreAdapter.VH> {

    private final List<ChannelItem> channels;
    private final Consumer<ChannelItem> onToggleFollow;

    public ChannelsExploreAdapter(List<ChannelItem> channels, Consumer<ChannelItem> onToggleFollow) {
        this.channels = channels;
        this.onToggleFollow = onToggleFollow;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_explore_row, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        ChannelItem c = channels.get(pos);
        h.tvIcon.setText(ChannelsUi.initial(c.name));
        h.tvIcon.getBackground().setTint(ChannelsUi.colorFor(c.name));
        h.tvName.setText(c.name);
        h.ivVerified.setVisibility(c.verified ? View.VISIBLE : View.GONE);
        h.tvFollowers.setText(c.followerCountLabel());
        ChannelsUi.applyFollowButtonStyle(h.btnFollow, c.following);
        h.btnFollow.setOnClickListener(v -> { if (onToggleFollow != null) onToggleFollow.accept(c); });
    }

    @Override public int getItemCount() { return channels.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvIcon, tvName, tvFollowers, btnFollow;
        ImageView ivVerified;
        VH(View v) {
            super(v);
            tvIcon = v.findViewById(R.id.tv_channel_icon);
            tvName = v.findViewById(R.id.tv_channel_name);
            ivVerified = v.findViewById(R.id.iv_channel_verified);
            tvFollowers = v.findViewById(R.id.tv_channel_followers);
            btnFollow = v.findViewById(R.id.btn_follow);
        }
    }
}
