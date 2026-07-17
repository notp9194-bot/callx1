package com.callx.app.feed;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * SuggestedAccountsAdapter — horizontal RecyclerView inside the feed's
 * "Suggested for you" card.
 *
 * Each item: avatar, name, handle, follower count, Follow/Following button.
 * Follow tap → toggles follow state immediately (optimistic UI) and calls
 * HomeFeedRepository.toggleFollow() in background.
 */
public class SuggestedAccountsAdapter extends RecyclerView.Adapter<SuggestedAccountsAdapter.VH> {

    // String[] = [uid, name, photoUrl, handle, followerCountLabel]
    private final List<String[]> users = new ArrayList<>();
    private final Set<String> followedUids = new HashSet<>();
    private OnFollowClickListener followListener;

    public interface OnFollowClickListener {
        void onFollowClicked(String uid, boolean currentlyFollowing);
    }

    public void setFollowListener(OnFollowClickListener l) { this.followListener = l; }

    public void setUsers(List<String[]> list) {
        users.clear();
        users.addAll(list);
        notifyDataSetChanged();
    }

    public void setFollowedUids(Set<String> uids) {
        followedUids.clear();
        followedUids.addAll(uids);
        notifyDataSetChanged();
    }

    public void markFollowed(String uid, boolean following) {
        if (following) followedUids.add(uid);
        else           followedUids.remove(uid);
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i)[0].equals(uid)) { notifyItemChanged(i); break; }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feed_suggested_user, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.bind(users.get(position));
    }

    @Override
    public int getItemCount() { return users.size(); }

    class VH extends RecyclerView.ViewHolder {
        CircleImageView ivAvatar;
        TextView        tvName, tvHandle, tvFollowers;
        Button          btnFollow;

        VH(@NonNull View v) {
            super(v);
            ivAvatar    = v.findViewById(R.id.iv_suggested_avatar);
            tvName      = v.findViewById(R.id.tv_suggested_name);
            tvHandle    = v.findViewById(R.id.tv_suggested_handle);
            tvFollowers = v.findViewById(R.id.tv_suggested_followers);
            btnFollow   = v.findViewById(R.id.btn_suggested_follow);
        }

        void bind(String[] user) {
            Context ctx = itemView.getContext();
            String uid     = user[0];
            String name    = user[1];
            String photo   = user[2];
            String handle  = user[3];
            String fcLabel = user[4];

            tvName.setText(name.isEmpty() ? "User" : name);
            tvHandle.setText(handle.isEmpty() ? "" : "@" + handle);
            tvFollowers.setText(fcLabel);

            if (!photo.isEmpty()) {
                Glide.with(ctx)
                     .load(photo)
                     .apply(RequestOptions.circleCropTransform())
                     .placeholder(R.drawable.ic_person)
                     .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }

            boolean isFollowing = followedUids.contains(uid);
            applyFollowState(btnFollow, isFollowing, ctx);

            btnFollow.setOnClickListener(v -> {
                boolean now = followedUids.contains(uid);
                if (now) followedUids.remove(uid);
                else     followedUids.add(uid);
                applyFollowState(btnFollow, !now, ctx);
                if (followListener != null) followListener.onFollowClicked(uid, now);
            });

            itemView.setOnClickListener(v -> {
                Intent i = new Intent(ctx, UserReelsActivity.class);
                i.putExtra("uid", uid);
                ctx.startActivity(i);
            });
            ivAvatar.setOnClickListener(v -> itemView.callOnClick());
        }

        void applyFollowState(Button btn, boolean following, Context ctx) {
            if (following) {
                btn.setText("Following");
                btn.setBackgroundResource(R.drawable.bg_filter_chip_unselected);
                btn.setTextColor(0xFFCCCCCC);
            } else {
                btn.setText("Follow");
                btn.setBackgroundResource(R.drawable.bg_filter_chip_selected);
                btn.setTextColor(0xFFFFFFFF);
            }
        }
    }
}
