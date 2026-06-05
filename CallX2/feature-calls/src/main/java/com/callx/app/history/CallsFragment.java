package com.callx.app.history;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.calls.R;

import com.callx.app.history.CallHistoryAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.models.CallLog;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;
import com.google.firebase.auth.FirebaseAuth;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.history.ContactCallHistoryAdapter;
import android.widget.Button;
import android.os.Handler;
import android.os.Looper;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

import java.util.*;
import java.util.concurrent.Executors;
import com.callx.app.call.CallActivity;

public class CallsFragment extends Fragment implements CallHistoryAdapter.SelectionListener {

    private final List<CallLog> allLogs = new ArrayList<>();
    private final List<CallLog> logs    = new ArrayList<>();
    private CallHistoryAdapter adapter;
    private View emptyState;

    private LinearLayout llOnlineUsers;
    private LinearLayout llOnlinePanel;
    private TextView tvNoOnline;
    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    // Filter chips
    private TextView chipAll, chipMissed, chipContacts, chipNonspam, chipSpam;
    private String activeFilter = "all";

    // Search
    private EditText etSearch;
    private String searchQuery = "";

    // Real-time online listeners — track per-uid so we can remove them
    private final Map<String, ValueEventListener> onlineListeners = new HashMap<>();
    private final Map<String, User> onlineUsersMap = new LinkedHashMap<>();
    private ValueEventListener contactsListener;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);

        RecyclerView rv = v.findViewById(R.id.rv_calls);
        emptyState      = v.findViewById(R.id.empty_calls);
        llOnlineUsers   = v.findViewById(R.id.ll_online_users);
        llOnlinePanel   = v.findViewById(R.id.ll_online_panel);
        tvNoOnline      = v.findViewById(R.id.tv_no_online);
        llSelectionBar  = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount = v.findViewById(R.id.tv_selected_count);

        chipAll      = v.findViewById(R.id.chip_all);
        chipMissed   = v.findViewById(R.id.chip_missed);
        chipContacts = v.findViewById(R.id.chip_contacts);
        chipNonspam  = v.findViewById(R.id.chip_nonspam);
        chipSpam     = v.findViewById(R.id.chip_spam);
        etSearch     = v.findViewById(R.id.et_search_calls);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallHistoryAdapter(logs, this);
        rv.setAdapter(adapter);

        // ── Contact click → BottomSheet (Reels-profile style) ──
        adapter.setOnContactClickListener((log, resolvedPhoto) ->
            showContactBottomSheet(log, resolvedPhoto));

        // Selection bar buttons
        v.findViewById(R.id.btn_cancel_selection_calls).setOnClickListener(x -> {
            adapter.clearSelection();
            if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        });
        v.findViewById(R.id.btn_select_all_calls).setOnClickListener(x -> {
            adapter.selectAll();
            updateSelectionCount();
        });
        v.findViewById(R.id.btn_delete_selected_calls).setOnClickListener(x ->
            confirmDeleteSelected());

        View tvViewContacts = v.findViewById(R.id.tv_view_contacts);
        if (tvViewContacts != null) {
            tvViewContacts.setOnClickListener(x -> {
                if (getContext() != null)
                    startActivity(new Intent().setClassName(
                        getContext(), "com.callx.app.activities.AllContactsActivity"));
            });
        }

        setupChip(chipAll,      "all");
        setupChip(chipMissed,   "missed");
        setupChip(chipContacts, "contacts");
        setupChip(chipNonspam,  "nonspam");
        setupChip(chipSpam,     "spam");

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int i, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    applyFilter();
                }
                @Override public void afterTextChanged(Editable e) {}
            });
        }

        loadOnlineContactsRealTime();
        loadCallLogs();
        return v;
    }

    // ── Contact BottomSheet (Reels/Profile style) ──────────────────────────
    private void showContactBottomSheet(CallLog log, String resolvedPhoto) {
        if (getContext() == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_contact_call, null);
        sheet.setContentView(sv);

        CircleImageView ivAvatar  = sv.findViewById(R.id.iv_avatar_sheet);
        TextView tvName           = sv.findViewById(R.id.tv_name_sheet);
        TextView tvStatus         = sv.findViewById(R.id.tv_status_sheet);
        View onlineDot            = sv.findViewById(R.id.view_online_dot_sheet);
        ImageView storyRing       = sv.findViewById(R.id.iv_story_ring_sheet);
        View btnMessage           = sv.findViewById(R.id.btn_message_sheet);
        View btnVoice             = sv.findViewById(R.id.btn_voice_call_sheet);
        View btnVideo             = sv.findViewById(R.id.btn_video_call_sheet);
        View btnHistory           = sv.findViewById(R.id.btn_call_history_sheet);

        // Social platform buttons
        View btnXSheet            = sv.findViewById(R.id.btn_x_sheet);
        View btnReelsSheet        = sv.findViewById(R.id.btn_reels_sheet);
        View btnYoutubeSheet      = sv.findViewById(R.id.btn_youtube_sheet);
        CircleImageView ivAnimX   = sv.findViewById(R.id.iv_anim_x_sheet);
        CircleImageView ivAnimReel= sv.findViewById(R.id.iv_anim_reel_sheet);
        CircleImageView ivAnimYt  = sv.findViewById(R.id.iv_anim_youtube_sheet);

        // Follow / Subscribe rows
        View layoutXRow           = sv.findViewById(R.id.layout_x_follow_row);
        View layoutReelsRow       = sv.findViewById(R.id.layout_reels_follow_row);
        View layoutYtRow          = sv.findViewById(R.id.layout_youtube_subscribe_row);
        TextView tvXCount         = sv.findViewById(R.id.tv_x_followers_count);
        TextView tvReelsCount     = sv.findViewById(R.id.tv_reels_followers_count);
        TextView tvYtCount        = sv.findViewById(R.id.tv_youtube_subs_count);
        Button btnXFollow         = sv.findViewById(R.id.btn_x_follow_action);
        Button btnReelsFollow     = sv.findViewById(R.id.btn_reels_follow_action);
        Button btnYtSubscribe     = sv.findViewById(R.id.btn_youtube_subscribe_action);

        // Set name
        tvName.setText(log.partnerName != null ? log.partnerName : "Unknown");

        // Load avatar (use resolved photo if already cached, else load from Firebase)
        if (resolvedPhoto != null && !resolvedPhoto.isEmpty()) {
            Glide.with(getContext()).load(resolvedPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(ivAvatar);
        }

        // Check online status + fetch latest photo in one go
        if (log.partnerUid != null) {
            FirebaseUtils.getUserRef(log.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        Boolean isOnline = snap.child("online").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(isOnline)) {
                            if (onlineDot != null) onlineDot.setVisibility(View.VISIBLE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Online");
                                tvStatus.setTextColor(getResources().getColor(
                                    R.color.brand_accent, null));
                            }
                        } else {
                            if (onlineDot != null) onlineDot.setVisibility(View.GONE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Offline");
                                tvStatus.setTextColor(getResources().getColor(
                                    R.color.text_muted, null));
                            }
                        }
                        // Load fresh photo
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String thumb = snap.child("thumbUrl").getValue(String.class);
                        String url   = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url != null && !url.isEmpty() && getContext() != null && ivAvatar != null)
                            Glide.with(getContext()).load(url)
                                .apply(RequestOptions.circleCropTransform())
                                .placeholder(R.drawable.ic_person)
                                .into(ivAvatar);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        // Avatar tap → fetch full photoUrl from Firebase → zoom dialog
        ivAvatar.setOnClickListener(x -> {
            if (getContext() == null || log.partnerUid == null) return;
            // Fetch full (non-thumb) photoUrl for best quality
            FirebaseUtils.getUserRef(log.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String fullPhoto = snap.child("photoUrl").getValue(String.class);
                        // Fall back to resolvedPhoto (thumb) if full not available
                        String toShow = (fullPhoto != null && !fullPhoto.isEmpty())
                            ? fullPhoto : resolvedPhoto;
                        showAvatarZoom(toShow);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(resolvedPhoto);
                    }
                });
        });

        // Message button
        btnMessage.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.activities.ChatActivity");
            i.putExtra("partnerUid",  log.partnerUid);
            i.putExtra("partnerName", log.partnerName != null ? log.partnerName : "");
            startActivity(i);
        });

        // Voice Call button
        btnVoice.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.call.CallActivity");
            i.putExtra("partnerUid",  log.partnerUid);
            i.putExtra("partnerName", log.partnerName != null ? log.partnerName : "");
            i.putExtra("isCaller", true);
            i.putExtra("video", false);
            startActivity(i);
        });

        // Video Call button
        btnVideo.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.call.CallActivity");
            i.putExtra("partnerUid",  log.partnerUid);
            i.putExtra("partnerName", log.partnerName != null ? log.partnerName : "");
            i.putExtra("isCaller", true);
            i.putExtra("video", true);
            startActivity(i);
        });

        // Call History button → open contact-specific call history sheet
        btnHistory.setOnClickListener(x -> {
            sheet.dismiss();
            showCallHistorySheet(log, resolvedPhoto);
        });

        // ── Social Buttons: X / Reels / YouTube ──────────────────────────
        // Load avatar peek images + wire open actions + follow/subscribe rows
        if (log.partnerUid != null) {
            // Arrays to track loaded avatars for animation
            final CircleImageView[] peekViews = {ivAnimX, ivAnimReel, ivAnimYt};
            final Handler[] sheetAnimHandler = {new Handler(Looper.getMainLooper())};
            final boolean[] sheetAnimRunning = {false};
            final Runnable[] sheetAnimRunnable = {null};

            loadSocialButtons(sv, log.partnerUid,
                btnXSheet, btnReelsSheet, btnYoutubeSheet,
                ivAnimX, ivAnimReel, ivAnimYt,
                layoutXRow, layoutReelsRow, layoutYtRow,
                tvXCount, tvReelsCount, tvYtCount,
                btnXFollow, btnReelsFollow, btnYtSubscribe,
                sheet,
                peekViews, sheetAnimHandler, sheetAnimRunning, sheetAnimRunnable);

            // Stop animation when sheet closes
            sheet.setOnDismissListener(d -> {
                sheetAnimRunning[0] = false;
                sheetAnimHandler[0].removeCallbacks(sheetAnimRunnable[0]);
                for (CircleImageView iv : peekViews) {
                    if (iv != null) { iv.setVisibility(View.INVISIBLE); iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f); }
                }
            });
        }

        sheet.show();
    }

    // ── Social buttons loader ────────────────────────────────────────────────
    /**
     * For the call bottom sheet, loads:
     *  1. Avatar peek images on X / Reels / YouTube buttons (from each platform's Firebase node)
     *  2. Wires button click → open respective profile/channel activity
     *  3. Shows follow/subscriber count row + Follow/Unfollow/Subscribe button
     *
     * Uses same Firebase paths as UserReelsActivity & XProfileSheet:
     *   X avatar        → x/users/{uid}/photoUrl
     *   Reels avatar    → reels/users/{uid}/photoUrl or thumbUrl
     *   YouTube avatar  → youtube/channels/{uid}/thumbUrl or photoUrl
     *   X followers     → x/followers/{uid}   (childrenCount) or x/users/{uid}/followerCount
     *   Reels followers → reels/followers/{uid} (childrenCount)
     *   YouTube subs    → youtube/channels/{uid}/subscriberCount
     */
    private void loadSocialButtons(
            View sv, String partnerUid,
            View btnXSheet, View btnReelsSheet, View btnYoutubeSheet,
            CircleImageView ivAnimX, CircleImageView ivAnimReel, CircleImageView ivAnimYt,
            View layoutXRow, View layoutReelsRow, View layoutYtRow,
            TextView tvXCount, TextView tvReelsCount, TextView tvYtCount,
            Button btnXFollow, Button btnReelsFollow, Button btnYtSubscribe,
            BottomSheetDialog sheet,
            CircleImageView[] peekViews,
            Handler[] sheetAnimHandler,
            boolean[] sheetAnimRunning,
            Runnable[] sheetAnimRunnable) {

        if (getContext() == null) return;
        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        final com.google.firebase.database.FirebaseDatabase db =
            com.google.firebase.database.FirebaseDatabase.getInstance(DB);
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        // ── X ──────────────────────────────────────────────────────────────
        db.getReference("x/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;

                    // Avatar peek — load X profile pic
                    String xPhoto = snap.child("photoUrl").getValue(String.class);
                    if (xPhoto != null && !xPhoto.isEmpty() && ivAnimX != null) {
                        Glide.with(getContext()).load(xPhoto)
                            .circleCrop().placeholder(R.drawable.ic_person).into(ivAnimX);
                    }
                    // Start loop after first (X) avatar loads — others load in background
                    startSheetAvatarPeekLoop(peekViews, sheetAnimHandler, sheetAnimRunning, sheetAnimRunnable);

                    // Follower count from x/users/{uid}/followerCount
                    Long xFollowers = snap.child("followerCount").getValue(Long.class);
                    long xFCount = xFollowers != null ? xFollowers : 0;
                    if (tvXCount != null) tvXCount.setText(formatSocialCount(xFCount) + " Followers");
                    if (layoutXRow != null) layoutXRow.setVisibility(View.VISIBLE);

                    // Follow state
                    if (myUid != null && btnXFollow != null) {
                        db.getReference("x/followers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isFollowing = { ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class)) };
                                    updateXFollowBtn(btnXFollow, isFollowing[0]);
                                    btnXFollow.setOnClickListener(v -> {
                                        isFollowing[0] = !isFollowing[0];
                                        updateXFollowBtn(btnXFollow, isFollowing[0]);
                                        if (isFollowing[0]) {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("x/following").child(myUid).child(partnerUid).setValue(true);
                                            db.getReference("x/users").child(partnerUid).child("followerCount")
                                                .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                                                    @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData d) {
                                                        Long v2 = d.getValue(Long.class); d.setValue(v2 == null ? 1 : v2 + 1); return com.google.firebase.database.Transaction.success(d);
                                                    }
                                                    @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                                                });
                                            if (tvXCount != null) updateCountTV(tvXCount, 1, "Followers");
                                        } else {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("x/following").child(myUid).child(partnerUid).removeValue();
                                            db.getReference("x/users").child(partnerUid).child("followerCount")
                                                .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                                                    @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData d) {
                                                        Long v2 = d.getValue(Long.class); d.setValue(v2 == null ? 0 : Math.max(0, v2 - 1)); return com.google.firebase.database.Transaction.success(d);
                                                    }
                                                    @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                                                });
                                            if (tvXCount != null) updateCountTV(tvXCount, -1, "Followers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    // X button → open XProfileSheet
                    if (btnXSheet != null) {
                        btnXSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            try {
                                Class<?> cls = Class.forName("com.callx.app.activities.XProfileSheet");
                                java.lang.reflect.Method m = cls.getMethod("showProfile",
                                    androidx.fragment.app.FragmentManager.class, String.class);
                                m.invoke(null, getParentFragmentManager(), partnerUid);
                            } catch (Exception ex) {
                                if (getContext() != null)
                                    Toast.makeText(getContext(), "X profile not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── Reels ──────────────────────────────────────────────────────────
        db.getReference("reels/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;

                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String reelPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (reelPhoto != null && !reelPhoto.isEmpty() && ivAnimReel != null) {
                        // Visibility managed by peek loop
                        Glide.with(getContext()).load(reelPhoto)
                            .circleCrop().placeholder(R.drawable.ic_person).into(ivAnimReel);
                    }

                    // Reels follower count
                    db.getReference("reels/followers").child(partnerUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot fSnap) {
                                long cnt = fSnap.getChildrenCount();
                                if (tvReelsCount != null) tvReelsCount.setText(formatSocialCount(cnt) + " Followers");
                                if (layoutReelsRow != null) layoutReelsRow.setVisibility(View.VISIBLE);

                                if (myUid != null && btnReelsFollow != null) {
                                    boolean[] isFollowing = { fSnap.hasChild(myUid) };
                                    updateReelsFollowBtn(btnReelsFollow, isFollowing[0]);
                                    btnReelsFollow.setOnClickListener(v -> {
                                        isFollowing[0] = !isFollowing[0];
                                        updateReelsFollowBtn(btnReelsFollow, isFollowing[0]);
                                        if (isFollowing[0]) {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("reels/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvReelsCount != null) updateCountTV(tvReelsCount, 1, "Followers");
                                        } else {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("reels/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvReelsCount != null) updateCountTV(tvReelsCount, -1, "Followers");
                                        }
                                    });
                                }
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });

                    // Reels button → open UserReelsActivity
                    if (btnReelsSheet != null) {
                        btnReelsSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            if (getContext() == null) return;
                            try {
                                Class<?> cls = Class.forName("com.callx.app.activities.UserReelsActivity");
                                android.content.Intent i = new android.content.Intent(getContext(), cls);
                                i.putExtra("uid", partnerUid);
                                startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(getContext(), "Reels not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── YouTube ────────────────────────────────────────────────────────
        db.getReference("youtube/channels").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;

                    String ytThumb = snap.child("thumbUrl").getValue(String.class);
                    String ytPhoto = snap.child("photoUrl").getValue(String.class);
                    String ytAvatar = (ytThumb != null && !ytThumb.isEmpty()) ? ytThumb : ytPhoto;
                    if (ytAvatar != null && !ytAvatar.isEmpty() && ivAnimYt != null) {
                        // Visibility managed by peek loop
                        Glide.with(getContext()).load(ytAvatar)
                            .circleCrop().placeholder(R.drawable.ic_person).into(ivAnimYt);
                    }

                    Long subCount = snap.child("subscriberCount").getValue(Long.class);
                    long ytSubs = subCount != null ? subCount : 0;
                    if (tvYtCount != null) tvYtCount.setText(formatSocialCount(ytSubs) + " Subscribers");
                    if (layoutYtRow != null) layoutYtRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnYtSubscribe != null) {
                        db.getReference("youtube/subscribers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isSubbed = { ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class)) };
                                    updateYtSubscribeBtn(btnYtSubscribe, isSubbed[0]);
                                    btnYtSubscribe.setOnClickListener(v -> {
                                        isSubbed[0] = !isSubbed[0];
                                        updateYtSubscribeBtn(btnYtSubscribe, isSubbed[0]);
                                        if (isSubbed[0]) {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("youtube/channels").child(partnerUid).child("subscriberCount")
                                                .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                                                    @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData d) {
                                                        Long v2 = d.getValue(Long.class); d.setValue(v2 == null ? 1 : v2 + 1); return com.google.firebase.database.Transaction.success(d);
                                                    }
                                                    @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                                                });
                                            if (tvYtCount != null) updateCountTV(tvYtCount, 1, "Subscribers");
                                        } else {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("youtube/channels").child(partnerUid).child("subscriberCount")
                                                .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                                                    @Override public com.google.firebase.database.Transaction.Result doTransaction(com.google.firebase.database.MutableData d) {
                                                        Long v2 = d.getValue(Long.class); d.setValue(v2 == null ? 0 : Math.max(0, v2 - 1)); return com.google.firebase.database.Transaction.success(d);
                                                    }
                                                    @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                                                });
                                            if (tvYtCount != null) updateCountTV(tvYtCount, -1, "Subscribers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    // YouTube button → open YouTubeChannelActivity
                    if (btnYoutubeSheet != null) {
                        btnYoutubeSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            if (getContext() == null) return;
                            try {
                                Class<?> cls = Class.forName("com.callx.app.activities.YouTubeChannelActivity");
                                android.content.Intent i = new android.content.Intent(getContext(), cls);
                                i.putExtra("uid", partnerUid);
                                startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(getContext(), "YouTube not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── Social button state helpers ──────────────────────────────────────────
    private void updateXFollowBtn(Button btn, boolean following) {
        if (btn == null) return;
        if (following) {
            btn.setText("Following");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF333333));
        } else {
            btn.setText("Follow");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF000000));
        }
    }

    private void updateReelsFollowBtn(Button btn, boolean following) {
        if (btn == null) return;
        if (following) {
            btn.setText("Following");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF555555));
        } else {
            btn.setText("Follow");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFDD2A7B));
        }
    }

    private void updateYtSubscribeBtn(Button btn, boolean subscribed) {
        if (btn == null) return;
        if (subscribed) {
            btn.setText("Subscribed");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF333333));
        } else {
            btn.setText("Subscribe");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF0000));
        }
    }

    /** Increment/decrement the count displayed in a "N Label" TextView */
    private void updateCountTV(TextView tv, int delta, String label) {
        try {
            String text = tv.getText().toString();
            String numStr = text.split(" ")[0].replace("K","000").replace("M","000000");
            long cur = Long.parseLong(numStr);
            tv.setText(formatSocialCount(Math.max(0, cur + delta)) + " " + label);
        } catch (Exception ignored) {}
    }

    // ── Sheet Avatar Peek Loop — same as UserReelsActivity ─────────────────
    /**
     * Exactly replicates UserReelsActivity's startAvatarPeekLoop() for the BottomSheet.
     * Loop: [X avatar] → zoom-in(450ms) → hold(3s) → zoom-out(400ms) → wait(3s) →
     *       [Reels avatar] → ... → [YouTube avatar] → ... → back to X → ∞
     *
     * Uses passed-in Handler/boolean/Runnable arrays so the caller can stop animation
     * when the sheet is dismissed (no memory leaks).
     */
    private void startSheetAvatarPeekLoop(
            CircleImageView[] views,
            Handler[] handlerArr,
            boolean[] runningArr,
            Runnable[] runnableArr) {

        if (runningArr[0]) return;   // already running
        runningArr[0] = true;

        // Initialize all peek views: invisible + scaled to 0
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }

        runnableArr[0] = new Runnable() {
            int idx = 0;

            @Override public void run() {
                if (!runningArr[0] || getContext() == null) return;

                CircleImageView iv = views[idx % views.length];
                idx++;

                if (iv == null) {
                    handlerArr[0].postDelayed(this, 500);
                    return;
                }

                // Reset to hidden/zero state
                iv.setScaleX(0f);
                iv.setScaleY(0f);
                iv.setAlpha(0f);
                iv.setVisibility(View.VISIBLE);

                // Zoom IN: 0 → 1.05 overshoot → 1.0, alpha 0 → 1
                ObjectAnimator scaleXIn = ObjectAnimator.ofFloat(iv, "scaleX", 0f, 1.05f, 1.0f);
                ObjectAnimator scaleYIn = ObjectAnimator.ofFloat(iv, "scaleY", 0f, 1.05f, 1.0f);
                ObjectAnimator alphaIn  = ObjectAnimator.ofFloat(iv, "alpha",  0f, 1f);
                scaleXIn.setDuration(450);
                scaleYIn.setDuration(450);
                alphaIn.setDuration(250);
                scaleXIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
                scaleYIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));

                AnimatorSet zoomIn = new AnimatorSet();
                zoomIn.playTogether(scaleXIn, scaleYIn, alphaIn);

                // Zoom OUT: 1.0 → 0, alpha 1 → 0  (after 3s hold)
                ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(iv, "scaleX", 1.0f, 0f);
                ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(iv, "scaleY", 1.0f, 0f);
                ObjectAnimator alphaOut  = ObjectAnimator.ofFloat(iv, "alpha",  1f,  0f);
                scaleXOut.setDuration(400);
                scaleYOut.setDuration(400);
                alphaOut.setDuration(400);
                scaleXOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
                scaleYOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));

                AnimatorSet zoomOut = new AnimatorSet();
                zoomOut.playTogether(scaleXOut, scaleYOut, alphaOut);
                zoomOut.setStartDelay(3000); // hold visible for 3 seconds

                AnimatorSet full = new AnimatorSet();
                full.playSequentially(zoomIn, zoomOut);
                full.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f);
                        iv.setScaleY(0f);
                        iv.setAlpha(0f);
                        // 3s gap then next button
                        if (runningArr[0] && getContext() != null)
                            handlerArr[0].postDelayed(runnableArr[0], 3000);
                    }
                });
                full.start();
            }
        };

        // Start with 1.5s initial delay (let sheet open fully first)
        handlerArr[0].postDelayed(runnableArr[0], 1500);
    }

    /** Format: 1200 → "1.2K", 1500000 → "1.5M", else raw */
    private String formatSocialCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── Online contacts — REAL-TIME per-user listeners ─────────────────────
    /**
     * Attaches a ValueEventListener on every contact's user node.
     * This means the online strip updates instantly when anyone goes online/offline —
     * no polling, no single-value snapshot.
     */
    private void loadOnlineContactsRealTime() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || getContext() == null) return;

        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                // Detach old per-user listeners
                removeOnlineListeners();
                onlineUsersMap.clear();

                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u == null) continue;
                    if (u.uid == null) u.uid = c.getKey();
                    if (u.uid == null) continue;
                    String contactUid = u.uid;
                    // Skip self
                    String myUid = FirebaseUtils.getCurrentUid();
                    if (myUid != null && myUid.equals(contactUid)) continue;

                    // Attach persistent listener for this contact
                    ValueEventListener perUserListener = new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot userSnap) {
                            Boolean online = userSnap.child("online").getValue(Boolean.class);
                            String  name   = userSnap.child("name").getValue(String.class);
                            String  photo  = userSnap.child("photoUrl").getValue(String.class);
                            String  thumb  = userSnap.child("thumbUrl").getValue(String.class);

                            if (name  != null) u.name     = name;
                            if (photo != null) u.photoUrl = photo;
                            if (thumb != null) u.thumbUrl = thumb;

                            if (Boolean.TRUE.equals(online)) {
                                onlineUsersMap.put(contactUid, u);
                            } else {
                                onlineUsersMap.remove(contactUid);
                            }
                            // Re-render every time any user's status changes
                            renderOnlineUsers(new ArrayList<>(onlineUsersMap.values()));
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    };
                    onlineListeners.put(contactUid, perUserListener);
                    FirebaseUtils.getUserRef(contactUid).addValueEventListener(perUserListener);
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getContactsRef(uid).addValueEventListener(contactsListener);
    }

    private void removeOnlineListeners() {
        for (Map.Entry<String, ValueEventListener> entry : onlineListeners.entrySet()) {
            FirebaseUtils.getUserRef(entry.getKey()).removeEventListener(entry.getValue());
        }
        onlineListeners.clear();
    }

    private void renderOnlineUsers(List<User> users) {
        if (getContext() == null || llOnlineUsers == null) return;
        llOnlineUsers.removeAllViews();
        if (users.isEmpty()) {
            if (llOnlinePanel != null) llOnlinePanel.setVisibility(View.GONE);
            return;
        }
        if (llOnlinePanel != null) llOnlinePanel.setVisibility(View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(getContext());
        for (User u : users) {
            View item = inf.inflate(R.layout.item_online_user, llOnlineUsers, false);
            CircleImageView iv = item.findViewById(R.id.iv_online_avatar);
            TextView tv = item.findViewById(R.id.tv_online_name);
            tv.setText(u.name != null ? u.name : "User");
            String onlineAvatar = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (onlineAvatar != null && !onlineAvatar.isEmpty()) {
                Glide.with(getContext()).load(onlineAvatar)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(iv);
            }
            // Online avatar tap → open bottom sheet for quick call
            final User finalU = u;
            item.setOnClickListener(x -> {
                CallLog fakeLog = new CallLog();
                fakeLog.partnerUid  = finalU.uid;
                fakeLog.partnerName = finalU.name;
                String photo = (finalU.thumbUrl != null && !finalU.thumbUrl.isEmpty())
                    ? finalU.thumbUrl : finalU.photoUrl;
                showContactBottomSheet(fakeLog, photo);
            });
            llOnlineUsers.addView(item);
        }
    }

    // ── Filters + Search ───────────────────────────────────────────────────
    private void setupChip(TextView chip, String filter) {
        if (chip == null) return;
        chip.setOnClickListener(x -> {
            activeFilter = filter;
            updateChipUI();
            applyFilter();
        });
    }

    private void updateChipUI() {
        if (getContext() == null) return;
        setChipState(chipAll,      "all");
        setChipState(chipMissed,   "missed");
        setChipState(chipContacts, "contacts");
        setChipState(chipNonspam,  "nonspam");
        setChipState(chipSpam,     "spam");
    }

    private void setChipState(TextView chip, String filter) {
        if (chip == null) return;
        boolean selected = filter.equals(activeFilter);
        chip.setBackgroundResource(selected ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(getResources().getColor(
            selected ? android.R.color.white : R.color.text_primary, null));
    }

    private void applyFilter() {
        logs.clear();
        for (CallLog l : allLogs) {
            if (!searchQuery.isEmpty()) {
                String name = l.partnerName != null ? l.partnerName.toLowerCase() : "";
                if (!name.contains(searchQuery)) continue;
            }
            String dir = l.direction == null ? "" : l.direction.toLowerCase();
            switch (activeFilter) {
                case "missed":
                    if (!dir.contains("missed")) continue;
                    break;
                case "contacts":
                    if (l.partnerName == null || l.partnerName.isEmpty()) continue;
                    break;
                case "spam":
                    if (!dir.contains("spam")) continue;
                    break;
                case "nonspam":
                    if (dir.contains("spam") || dir.contains("missed")) continue;
                    break;
                default:
                    break;
            }
            logs.add(l);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Call Logs — Offline-First ──────────────────────────────────────────
    private void loadCallLogs() {
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() -> {
                List<CallLogEntity> cached = db.callLogDao().getAllCallLogsSync();
                if (cached != null && !cached.isEmpty()) {
                    List<CallLog> roomLogs = new ArrayList<>();
                    for (CallLogEntity e : cached) {
                        CallLog l = new CallLog();
                        l.id          = e.id;
                        l.partnerUid  = e.partnerUid;
                        l.partnerName = e.partnerName;
                        l.direction   = e.direction;
                        l.mediaType   = e.mediaType;
                        l.timestamp   = e.timestamp;
                        l.duration    = e.duration;
                        roomLogs.add(l);
                    }
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (allLogs.isEmpty()) {
                                allLogs.addAll(roomLogs);
                                applyFilter();
                            }
                        });
                    }
                }
            });
        }

        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.getCallsRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                allLogs.clear();
                List<CallLogEntity> toSave = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    CallLog l = c.getValue(CallLog.class);
                    if (l != null) {
                        if (l.id == null) l.id = c.getKey();
                        allLogs.add(l);
                        CallLogEntity entity = new CallLogEntity();
                        entity.id          = l.id;
                        entity.partnerUid  = l.partnerUid;
                        entity.partnerName = l.partnerName;
                        entity.direction   = l.direction;
                        entity.mediaType   = l.mediaType;
                        entity.timestamp   = l.timestamp;
                        entity.duration    = l.duration;
                        toSave.add(entity);
                    }
                }
                Collections.sort(allLogs, (a, b) -> {
                    long ta = a.timestamp == null ? 0 : a.timestamp;
                    long tb = b.timestamp == null ? 0 : b.timestamp;
                    return Long.compare(tb, ta);
                });
                applyFilter();

                if (getContext() != null && !toSave.isEmpty()) {
                    AppDatabase db = AppDatabase.getInstance(getContext());
                    Executors.newSingleThreadExecutor().execute(() ->
                        db.callLogDao().insertCallLogs(toSave));
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    // ── Contact-specific Call History BottomSheet ──────────────────────────
    /**
     * Shows a second bottom sheet listing all calls (voice + video) with this contact.
     * Each row shows: type icon, label, time, duration, and a quick-call icon.
     */
    private void showCallHistorySheet(CallLog contactLog, String resolvedPhoto) {
        if (getContext() == null || contactLog.partnerUid == null) return;

        // Filter allLogs to only this contact's calls
        List<CallLog> contactLogs = new ArrayList<>();
        for (CallLog l : allLogs) {
            if (contactLog.partnerUid.equals(l.partnerUid)) {
                contactLogs.add(l);
            }
        }

        BottomSheetDialog histSheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_call_history, null);
        histSheet.setContentView(sv);

        CircleImageView ivAvatar = sv.findViewById(R.id.iv_history_avatar);
        TextView tvName          = sv.findViewById(R.id.tv_history_name);
        TextView tvCount         = sv.findViewById(R.id.tv_history_count);
        View     btnClose        = sv.findViewById(R.id.btn_close_history);
        RecyclerView rv          = sv.findViewById(R.id.rv_call_history_sheet);
        View llEmpty             = sv.findViewById(R.id.ll_history_empty);

        // Header
        tvName.setText(contactLog.partnerName != null ? contactLog.partnerName : "Unknown");
        int total = contactLogs.size();
        tvCount.setText(total + " call" + (total != 1 ? "s" : ""));

        // Avatar
        if (resolvedPhoto != null && !resolvedPhoto.isEmpty()) {
            Glide.with(getContext()).load(resolvedPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(ivAvatar);
        }

        // Avatar in history sheet → same full-photo zoom
        ivAvatar.setOnClickListener(x -> {
            if (getContext() == null || contactLog.partnerUid == null) return;
            FirebaseUtils.getUserRef(contactLog.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String fullPhoto = snap.child("photoUrl").getValue(String.class);
                        String toShow = (fullPhoto != null && !fullPhoto.isEmpty())
                            ? fullPhoto : resolvedPhoto;
                        showAvatarZoom(toShow);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(resolvedPhoto);
                    }
                });
        });

        // Close button
        btnClose.setOnClickListener(x -> histSheet.dismiss());

        // RecyclerView
        if (contactLogs.isEmpty()) {
            if (rv     != null) rv.setVisibility(View.GONE);
            if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
        } else {
            if (llEmpty != null) llEmpty.setVisibility(View.GONE);
            rv.setLayoutManager(new LinearLayoutManager(getContext()));
            rv.setAdapter(new ContactCallHistoryAdapter(contactLogs));
        }

        histSheet.show();
    }

    // ── Full-screen avatar zoom (pinch-to-zoom, same as ProfileActivity) ──
    /**
     * Opens a full-screen dialog with PhotoView (pinch-to-zoom support).
     * Always uses full photoUrl — not thumb — for maximum quality.
     */
    private void showAvatarZoom(String photoUrl) {
        if (getContext() == null) return;

        android.app.Dialog dialog = new android.app.Dialog(
            getContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        android.widget.FrameLayout root = new android.widget.FrameLayout(getContext());
        root.setBackgroundColor(0xEE000000);

        com.github.chrisbanes.photoview.PhotoView photoView =
            new com.github.chrisbanes.photoview.PhotoView(getContext());
        android.widget.FrameLayout.LayoutParams ivLp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f);
        photoView.setMediumScale(2f);
        photoView.setMaximumScale(5f);
        // Tap outside photo → dismiss
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());

        // Close button — top right
        android.widget.ImageButton btnClose = new android.widget.ImageButton(getContext());
        float dp = getResources().getDisplayMetrics().density;
        int closeSizePx = (int)(40 * dp);
        android.widget.FrameLayout.LayoutParams closeLp =
            new android.widget.FrameLayout.LayoutParams(closeSizePx, closeSizePx);
        closeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        closeLp.topMargin   = (int)(40 * dp);
        closeLp.rightMargin = (int)(16 * dp);
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(com.callx.app.calls.R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(getContext())
                .load(photoUrl)
                .placeholder(com.callx.app.calls.R.drawable.ic_person)
                .error(com.callx.app.calls.R.drawable.ic_person)
                .into(photoView);
        } else {
            photoView.setImageResource(com.callx.app.calls.R.drawable.ic_person);
        }

        root.addView(photoView);
        root.addView(btnClose);
        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    // ── Cleanup — remove real-time listeners when fragment is destroyed ────
    @Override public void onDestroyView() {
        super.onDestroyView();
        removeOnlineListeners();
        String uid = FirebaseUtils.getCurrentUid();
        if (uid != null && contactsListener != null)
            FirebaseUtils.getContactsRef(uid).removeEventListener(contactsListener);
    }

    // ── Selection ──────────────────────────────────────────────────────────
    @Override public void onSelectionStarted() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
    }
    @Override public void onSelectionChanged() { updateSelectionCount(); }
    @Override public void onSelectionCleared() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    private void updateSelectionCount() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (tvSelectedCount != null) tvSelectedCount.setText(count + " selected");
    }

    private void confirmDeleteSelected() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (count == 0) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Delete " + count + " call log" + (count > 1 ? "s" : "") + "?")
            .setMessage("Selected call history will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> deleteSelected())
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteSelected() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        List<CallLog> selected = adapter.getSelectedItems();
        for (CallLog l : selected)
            if (l.id != null) FirebaseUtils.getCallsRef(uid).child(l.id).removeValue();
        allLogs.removeAll(selected);
        adapter.clearSelection();
        applyFilter();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }
}
