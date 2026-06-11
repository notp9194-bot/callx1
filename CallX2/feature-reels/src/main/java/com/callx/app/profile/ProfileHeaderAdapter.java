package com.callx.app.profile;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.reels.R;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ProfileHeaderAdapter — Approach A scrolling fix.
 * ===================================================
 * Single-item RecyclerView adapter that inflates {@code layout_user_reels_profile_header.xml}
 * as position 0.  Used with ConcatAdapter so the profile header scrolls WITH the reel grid:
 *
 *   ConcatAdapter(profileHeaderAdapter, reelGridAdapter)  → rvReels
 *   ConcatAdapter(seriesHeaderAdapter,  seriesAdapter)    → rvSeries
 *
 * The Activity gets a reference to the HeaderVH (which holds all sub-views) via
 * {@link OnHeaderBoundListener#onBound(HeaderVH)} and sets up click listeners + data there.
 *
 * No fixed-height profile section exists in activity_user_reels.xml anymore — SwipeRefreshLayout
 * now contains only the RecyclerView (no height contention, clean pull-to-refresh gesture).
 */
public class ProfileHeaderAdapter extends RecyclerView.Adapter<ProfileHeaderAdapter.HeaderVH> {

    private static final int VIEW_TYPE_HEADER = 999;

    /** Fired once when the header ViewHolder is created and bound (or re-bound after recycle). */
    public interface OnHeaderBoundListener {
        void onBound(HeaderVH vh);
    }

    /** All public views inside layout_user_reels_profile_header.xml. */
    public static class HeaderVH extends RecyclerView.ViewHolder {
        public final CircleImageView ivAvatar;
        public final View            viewStoryRing;
        public final TextView        tvFollowers;
        public final TextView        tvFollowing;
        public final TextView        tvReelCount;
        public final View            layoutFollowersClick;
        public final View            layoutFollowingClick;
        public final LinearLayout    layoutMutualFollowers;
        public final CircleImageView ivMutual1;
        public final CircleImageView ivMutual2;
        public final CircleImageView ivMutual3;
        public final TextView        tvMutualFollowers;
        public final TextView        tvBio;
        public final View            layoutPhone;
        public final TextView        tvPhone;
        public final View            layoutWhatsapp;
        public final TextView        tvWhatsapp;
        public final View            layoutInstagram;
        public final TextView        tvInstagram;
        public final View            layoutYoutube;
        public final TextView        tvYoutube;
        public final View            layoutOtherLink;
        public final TextView        tvOtherLink;
        public final LinearLayout    layoutActions;
        public final View            btnMessage;
        public final View            btnAudioCall;
        public final View            btnVideoCall;
        public final View            btnOpenX;
        public final View            btnOpenYoutube;
        public final CircleImageView ivAnimChat;
        public final CircleImageView ivAnimX;
        public final CircleImageView ivAnimYoutube;

        public HeaderVH(@NonNull View v) {
            super(v);
            ivAvatar              = v.findViewById(R.id.iv_avatar);
            viewStoryRing         = v.findViewById(R.id.view_story_ring);
            tvFollowers           = v.findViewById(R.id.tv_followers);
            tvFollowing           = v.findViewById(R.id.tv_following);
            tvReelCount           = v.findViewById(R.id.tv_reel_count);
            layoutFollowersClick  = v.findViewById(R.id.layout_followers_click);
            layoutFollowingClick  = v.findViewById(R.id.layout_following_click);
            layoutMutualFollowers = v.findViewById(R.id.layout_mutual_followers);
            ivMutual1             = v.findViewById(R.id.iv_mutual_1);
            ivMutual2             = v.findViewById(R.id.iv_mutual_2);
            ivMutual3             = v.findViewById(R.id.iv_mutual_3);
            tvMutualFollowers     = v.findViewById(R.id.tv_mutual_followers);
            tvBio                 = v.findViewById(R.id.tv_bio);
            layoutPhone           = v.findViewById(R.id.layout_phone);
            tvPhone               = v.findViewById(R.id.tv_phone);
            layoutWhatsapp        = v.findViewById(R.id.layout_whatsapp);
            tvWhatsapp            = v.findViewById(R.id.tv_whatsapp);
            layoutInstagram       = v.findViewById(R.id.layout_instagram);
            tvInstagram           = v.findViewById(R.id.tv_instagram);
            layoutYoutube         = v.findViewById(R.id.layout_youtube);
            tvYoutube             = v.findViewById(R.id.tv_youtube);
            layoutOtherLink       = v.findViewById(R.id.layout_other_link);
            tvOtherLink           = v.findViewById(R.id.tv_other_link);
            layoutActions         = v.findViewById(R.id.layout_actions);
            btnMessage            = v.findViewById(R.id.btn_message);
            btnAudioCall          = v.findViewById(R.id.btn_audio_call);
            btnVideoCall          = v.findViewById(R.id.btn_video_call);
            btnOpenX              = v.findViewById(R.id.btn_open_x);
            btnOpenYoutube        = v.findViewById(R.id.btn_open_youtube);
            ivAnimChat            = v.findViewById(R.id.iv_anim_chat);
            ivAnimX               = v.findViewById(R.id.iv_anim_x);
            ivAnimYoutube         = v.findViewById(R.id.iv_anim_youtube);
        }
    }

    private OnHeaderBoundListener boundListener;

    public void setOnHeaderBoundListener(OnHeaderBoundListener listener) {
        this.boundListener = listener;
    }

    @NonNull
    @Override
    public HeaderVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.layout_user_reels_profile_header, parent, false);
        return new HeaderVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderVH holder, int position) {
        if (boundListener != null) boundListener.onBound(holder);
    }

    @Override
    public int getItemCount() { return 1; }

    @Override
    public int getItemViewType(int position) { return VIEW_TYPE_HEADER; }
}
