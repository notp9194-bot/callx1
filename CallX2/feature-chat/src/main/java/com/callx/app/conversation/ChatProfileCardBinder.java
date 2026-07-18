package com.callx.app.conversation;

import android.content.Intent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.LayoutChatProfileCardBinding;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.Constants;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

/**
 * ChatProfileCardBinder — binds the Instagram-style profile card that sits
 * just below the chat header capsule (see layout_chat_profile_card.xml,
 * included into activity_chat.xml as R.id.include_profile_card).
 *
 * Kept as a standalone helper (not inlined into ChatActivity) so the huge
 * ChatActivity.java doesn't grow further — this class owns everything
 * about the card: expand/collapse, avatar, Reels/X/YouTube stat chips
 * (REAL data — same Firebase nodes those modules already write to),
 * Subscribe toggle (YouTube), and the opt-in "View Community" button.
 *
 * Cross-module data access pattern:
 *   feature-chat has NO Gradle dependency on feature-reels/feature-x/
 *   feature-youtube (see ChatActivity#setupToolbar's reflection-based
 *   Reel/X/YouTube buttons). Reflection works for calling INTO those
 *   modules' Activities/statics. For simple read-only stat counts we
 *   instead read their well-known Firebase Realtime Database paths
 *   directly (reels/users/{uid}, x/users/{uid}, youtube/channels/{uid})
 *   — these paths are a stable data contract already relied on by
 *   multiple modules (see ReelFirebaseUtils / YouTubeFirebaseUtils),
 *   not a compiled class, so reading them here does not create a real
 *   module coupling. The Subscribe button mirrors YouTubeChannelActivity
 *   #toggleSubscribe()'s exact read/write shape for the same reason.
 */
public class ChatProfileCardBinder {

    private final FragmentActivity activity;
    private final LayoutChatProfileCardBinding binding;
    private final FirebaseDatabase firebase;

    private String partnerUid;
    private boolean expanded = false;
    private boolean youtubeSubscribed = false;

    public ChatProfileCardBinder(@NonNull FragmentActivity activity,
                                  @NonNull LayoutChatProfileCardBinding binding) {
        this.activity = activity;
        this.binding = binding;
        this.firebase = FirebaseDatabase.getInstance(Constants.DB_URL);
    }

    /** Call once from ChatActivity#setupToolbar() after partner fields are known. */
    public void bind(String partnerUid, String partnerName, String partnerPhoto, String partnerThumb) {
        this.partnerUid = partnerUid;
        binding.tvProfileCardName.setText(partnerName != null ? partnerName : "");

        String avatar = (partnerThumb != null && !partnerThumb.isEmpty()) ? partnerThumb : partnerPhoto;
        if (avatar != null && !avatar.isEmpty()) {
            Glide.with(activity).load(avatar).placeholder(R.drawable.ic_person)
                    .override(240, 240)
                    .circleCrop().into(binding.ivProfileCardAvatar);
        }

        if (partnerUid == null || partnerUid.isEmpty()) return;

        loadReelStats(partnerUid);
        loadXStats(partnerUid);
        loadYoutubeStats(partnerUid);
        loadCommunityAvailability(partnerUid);
    }

    /** Wire this to the header name/avatar row tap so the card expands/collapses. */
    public void toggleExpanded() {
        expanded = !expanded;
        View root = binding.getRoot();
        if (expanded) {
            root.setVisibility(View.VISIBLE);
            root.startAnimation(AnimationUtils.loadAnimation(activity, android.R.anim.fade_in));
        } else {
            root.setVisibility(View.GONE);
        }
    }

    // ── Reels stats (reels/users/{uid}/followerCount) ──────────────────────
    private void loadReelStats(String uid) {
        firebase.getReference("reels/users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists()) return; // partner has no Reels profile — leave chip hidden
                        long followers = longOrZero(snap.child("followerCount"));
                        binding.statReels.setVisibility(View.VISIBLE);
                        binding.tvStatReelsCount.setText(formatCount(followers));
                        binding.statReels.setOnClickListener(v -> openReelsProfile());
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void openReelsProfile() {
        try {
            Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
            Intent in = new Intent(activity, cls);
            in.putExtra("uid", partnerUid);
            activity.startActivity(in);
        } catch (ClassNotFoundException e) {
            Toast.makeText(activity, "Reels profile not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── X stats (x/users/{uid}/followerCount) ───────────────────────────────
    private void loadXStats(String uid) {
        firebase.getReference("x/users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists()) return;
                        long followers = longOrZero(snap.child("followerCount"));
                        binding.statX.setVisibility(View.VISIBLE);
                        binding.tvStatXCount.setText(formatCount(followers));
                        binding.statX.setOnClickListener(v -> openXProfile());
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void openXProfile() {
        try {
            Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
            java.lang.reflect.Method method = cls.getMethod("showProfile",
                    androidx.fragment.app.FragmentManager.class, String.class);
            method.invoke(null, activity.getSupportFragmentManager(), partnerUid);
        } catch (Exception e) {
            Toast.makeText(activity, "X profile not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── YouTube stats + Subscribe (youtube/channels/{uid}/subscriberCount) ──
    private void loadYoutubeStats(String uid) {
        firebase.getReference("youtube/channels").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists()) return;
                        long subs = longOrZero(snap.child("subscriberCount"));
                        binding.statYoutube.setVisibility(View.VISIBLE);
                        binding.tvStatYoutubeCount.setText(formatCount(subs));
                        binding.statYoutube.setOnClickListener(v -> openYoutubeChannel());

                        String myUid = currentUid();
                        if (myUid == null || myUid.equals(uid)) return; // can't subscribe to yourself
                        MaterialButton btn = binding.btnProfileCardSubscribe;
                        btn.setVisibility(View.VISIBLE);
                        firebase.getReference("youtube/subscriptions").child(myUid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot subsSnap) {
                                        youtubeSubscribed = subsSnap.hasChild(uid);
                                        btn.setText(youtubeSubscribed ? "Subscribed" : "Subscribe");
                                        btn.setOnClickListener(v -> toggleYoutubeSubscribe(uid, myUid, btn));
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                                });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    /** Mirrors YouTubeChannelActivity#toggleSubscribe()'s exact read/write shape. */
    private void toggleYoutubeSubscribe(String channelUid, String myUid, MaterialButton btn) {
        DatabaseReference subsRef = firebase.getReference("youtube/subscriptions").child(myUid).child(channelUid);
        DatabaseReference subscribersRef = firebase.getReference("youtube/subscribers").child(channelUid).child(myUid);
        DatabaseReference countRef = firebase.getReference("youtube/channels").child(channelUid).child("subscriberCount");

        if (youtubeSubscribed) {
            subsRef.removeValue();
            subscribersRef.removeValue();
            countRef.setValue(ServerValue.increment(-1));
        } else {
            subsRef.setValue(true);
            subscribersRef.setValue(true);
            countRef.setValue(ServerValue.increment(1));
        }
        youtubeSubscribed = !youtubeSubscribed;
        btn.setText(youtubeSubscribed ? "Subscribed" : "Subscribe");
    }

    private void openYoutubeChannel() {
        try {
            Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
            Intent in = new Intent(activity, cls);
            in.putExtra("uid", partnerUid);
            activity.startActivity(in);
        } catch (ClassNotFoundException e) {
            Toast.makeText(activity, "YouTube channel not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Community (opt-in — button only shows if partner enabled one) ──────
    private void loadCommunityAvailability(String uid) {
        CommunityRepository.getInstance(activity).checkHasCommunity(uid, communityId -> {
            if (communityId == null) return;
            activity.runOnUiThread(() -> {
                binding.btnProfileCardViewCommunity.setVisibility(View.VISIBLE);
                binding.btnProfileCardViewCommunity.setOnClickListener(v -> {
                    try {
                        Class<?> cls = Class.forName("com.callx.app.community.CommunityActivity");
                        Intent in = new Intent(activity, cls);
                        in.putExtra("communityId", communityId);
                        activity.startActivity(in);
                    } catch (ClassNotFoundException e) {
                        Toast.makeText(activity, "Community not available", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    @Nullable
    private String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
    }

    private static long longOrZero(DataSnapshot snap) {
        Long v = snap.getValue(Long.class);
        return v != null ? v : 0L;
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
