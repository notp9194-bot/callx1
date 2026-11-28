package com.callx.app.history;
import com.callx.app.utils.AlertDialogStyler;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.SimpleItemAnimator;
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
// PERF (deep avatar pipeline parity — see CallAvatarBinder): tiered/
// versioned URL + L2/L3 reuse, same shape as CallHistoryAdapter's rows so a
// partner's avatar decoded there is reused instantly here.
import com.callx.app.cache.CallAvatarBinder;
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

    // v950: ONE static single-thread executor (was a per-fragment executor created on every tab return
    // and shut down in onDestroyView). Room reads/writes + Firebase snapshot parsing run here, off main.
    private static final java.util.concurrent.ExecutorService DB_EXEC =
        Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final List<CallLog> allLogs = new ArrayList<>();
    private final List<CallLog> logs    = new ArrayList<>();
    private CallHistoryAdapter adapter;
    private View rootView;                 // null between onDestroyView and next onCreateView

    // v950: lazily-inflated header parts (ViewStubs)
    private ViewStub stubSelection, stubOnline, stubEmpty;
    private View emptyState;
    private LinearLayout llOnlineUsers;
    private LinearLayout llOnlinePanel;
    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    // Filter chips
    private TextView chipAll, chipMissed, chipContacts, chipNonspam, chipSpam;
    private String activeFilter = "all";

    // Search (debounced)
    private EditText etSearch;
    private String searchQuery = "";
    private final Runnable searchRunnable = () -> applyFilter(false);

    // Online strip: only /users/{uid}/online is listened per contact; renders are coalesced + diffed.
    private static final class OnlineHolder {
        View item; CircleImageView iv; TextView tv; String avatar; String name; User user;
    }
    private final Map<String, ValueEventListener> onlineListeners = new HashMap<>();
    private final Map<String, User> contactUsers = new HashMap<>();
    private final Map<String, User> onlineUsersMap = new LinkedHashMap<>();
    private final Map<String, OnlineHolder> onlineViews = new HashMap<>();
    private ValueEventListener contactsListener;
    private boolean onlineRenderPending;
    private final Runnable onlineRenderRunnable = () -> {
        onlineRenderPending = false;
        renderOnlineUsers();
    };

    // Call logs listener (kept so it can be removed — it used to leak one listener per tab return)
    private ValueEventListener callsListener;
    private DatabaseReference callsRef;
    private volatile Integer lastSig;      // content signature of the last applied/saved data
    private boolean firebaseLogsLoaded;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);
        rootView = v;

        RecyclerView rv = v.findViewById(R.id.rv_calls);
        stubEmpty     = v.findViewById(R.id.stub_empty_calls);
        stubOnline    = v.findViewById(R.id.stub_online_panel);
        stubSelection = v.findViewById(R.id.stub_selection_bar);
        emptyState = null; llOnlinePanel = null; llOnlineUsers = null;
        llSelectionBar = null; tvSelectedCount = null;
        onlineViews.clear();
        lastSig = null;
        firebaseLogsLoaded = false;
        searchQuery = "";

        chipAll      = v.findViewById(R.id.chip_all);
        chipMissed   = v.findViewById(R.id.chip_missed);
        chipContacts = v.findViewById(R.id.chip_contacts);
        chipNonspam  = v.findViewById(R.id.chip_nonspam);
        chipSpam     = v.findViewById(R.id.chip_spam);
        etSearch     = v.findViewById(R.id.et_search_calls);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setHasFixedSize(true);
        RecyclerView.ItemAnimator ia = rv.getItemAnimator();
        if (ia instanceof SimpleItemAnimator) ((SimpleItemAnimator) ia).setSupportsChangeAnimations(false);
        com.callx.app.utils.RecyclerViewFrictionTuner.applyReducedFriction(rv);
        adapter = new CallHistoryAdapter(logs, this);
        rv.setAdapter(adapter);
        rv.setItemViewCacheSize(4);
        warmRowPool(rv);

        // PERF (deep avatar pipeline parity — see CallAvatarBinder): velocity-
        // based avatar prefetch, same technique/thresholds ChatsFragment uses
        // for ChatAvatarBinder.prefetch() — fast fling skips prefetch
        // entirely, slow/deliberate scroll warms several rows ahead via
        // DiskCacheStrategy.DATA (bytes only, no speculative decode) until a
        // row actually becomes visible and CallHistoryAdapter#onBindViewHolder
        // calls the real CallAvatarBinder.bind().
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long lastTimeMs = 0L;

            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null || getContext() == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;

                long now = android.os.SystemClock.elapsedRealtime();
                long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                lastTimeMs = now;

                CallHistoryAdapter ad = adapter;
                if (ad != null) ad.prefetchAvatars(getContext(), lastVisible + 1, velocity);
            }
        });

        // ── Contact click → BottomSheet (Reels-profile style) ──
        adapter.setOnContactClickListener((log, resolvedPhoto) ->
            showContactBottomSheet(log, resolvedPhoto));

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
                    uiHandler.removeCallbacks(searchRunnable);
                    uiHandler.postDelayed(searchRunnable, 120L);
                }
                @Override public void afterTextChanged(Editable e) {}
            });
        }

        // Instant paint: Room cache (background read) first; fragment instance reuse shows retained data.
        if (!allLogs.isEmpty()) applyFilter(false);
        loadCallLogsFromRoom();
        // Firebase listeners (N contacts + calls) are attached AFTER the first frame so they never
        // compete with inflate/measure/draw of the header.
        v.post(() -> {
            if (rootView == null) return;
            loadCallLogsFromFirebase();
            loadOnlineContactsRealTime();
        });
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
        // PERF (deep avatar pipeline parity — see CallAvatarBinder)
        if (resolvedPhoto != null && !resolvedPhoto.isEmpty()) {
            CallAvatarBinder.bind(getContext(), ivAvatar, resolvedPhoto, 0,
                CallAvatarBinder.SHEET_TIER, R.drawable.ic_person);
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
                        // PERF (deep avatar pipeline parity — see CallAvatarBinder)
                        if (url != null && !url.isEmpty() && getContext() != null && ivAvatar != null)
                            CallAvatarBinder.bind(getContext(), ivAvatar, url, 0,
                                CallAvatarBinder.SHEET_TIER, R.drawable.ic_person);
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
                        showAvatarZoom(ivAvatar, toShow);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(ivAvatar, resolvedPhoto);
                    }
                });
        });

        // Message button
        btnMessage.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.conversation.ChatActivity");
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
            ? FirebaseUtils.getCurrentUid() : null;

        // ── X ──────────────────────────────────────────────────────────────
        db.getReference("x/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;

                    // Avatar peek — load X profile pic
                    String xPhoto = snap.child("photoUrl").getValue(String.class);
                    if (xPhoto != null && !xPhoto.isEmpty() && ivAnimX != null) {
                        Glide.with(getContext()).load(xPhoto)
                            .override(96, 96)
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
                                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
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
                            .override(96, 96)
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
                                Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
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
                            .override(96, 96)
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
                                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
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
            com.callx.app.utils.FollowButtonStyler.applyPrimaryTint(btn);
            btn.setTextColor(com.callx.app.utils.FollowButtonStyler.textColor(btn.getContext()));
        }
    }

    private void updateReelsFollowBtn(Button btn, boolean following) {
        if (btn == null) return;
        if (following) {
            btn.setText("Following");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF555555));
        } else {
            btn.setText("Follow");
            com.callx.app.utils.FollowButtonStyler.applyPrimaryTint(btn);
            btn.setTextColor(com.callx.app.utils.FollowButtonStyler.textColor(btn.getContext()));
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

    // ── Online contacts — REAL-TIME (v950: light) ───────────────────────────
    /**
     * One contacts listener + ONE tiny listener per contact on /users/{uid}/online (a boolean) —
     * previously every contact's whole user node. Contact add/remove is diffed (listeners are no longer
     * detached + re-attached on every contacts change), and all status events coalesce into one render.
     */
    private void loadOnlineContactsRealTime() {
        final String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || getContext() == null || contactsListener != null) return;

        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Set<String> present = new HashSet<>();
                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u == null) continue;
                    if (u.uid == null) u.uid = c.getKey();
                    if (u.uid == null) continue;
                    final String cu = u.uid;
                    if (cu.equals(myUid)) continue;
                    present.add(cu);
                    contactUsers.put(cu, u);
                    if (onlineUsersMap.containsKey(cu)) {       // refresh name/photo of an online user
                        onlineUsersMap.put(cu, u);
                        scheduleOnlineRender();
                    }
                    if (!onlineListeners.containsKey(cu)) attachOnlineListener(cu);
                }
                boolean changed = false;
                Iterator<Map.Entry<String, ValueEventListener>> it = onlineListeners.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, ValueEventListener> e = it.next();
                    if (present.contains(e.getKey())) continue;
                    FirebaseUtils.getUserRef(e.getKey()).child("online").removeEventListener(e.getValue());
                    it.remove();
                    contactUsers.remove(e.getKey());
                    if (onlineUsersMap.remove(e.getKey()) != null) changed = true;
                }
                if (changed) scheduleOnlineRender();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getContactsRef(myUid).addValueEventListener(contactsListener);
    }

    private void attachOnlineListener(final String cu) {
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                boolean on = Boolean.TRUE.equals(s.getValue(Boolean.class));
                User u = contactUsers.get(cu);
                if (u == null) return;
                if (on) {
                    if (!onlineUsersMap.containsKey(cu)) {       // unchanged status → no work at all
                        onlineUsersMap.put(cu, u);
                        if (u.name == null || (u.photoUrl == null && u.thumbUrl == null)) hydrateUser(cu, u);
                        scheduleOnlineRender();
                    }
                } else if (onlineUsersMap.remove(cu) != null) {
                    scheduleOnlineRender();
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        onlineListeners.put(cu, l);
        FirebaseUtils.getUserRef(cu).child("online").addValueEventListener(l);
    }

    /** Contact node had no name/photo: one single-shot read (only when that user goes online). */
    private void hydrateUser(final String cu, final User u) {
        FirebaseUtils.getUserRef(cu).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String name  = snap.child("name").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String thumb = snap.child("thumbUrl").getValue(String.class);
                boolean ch = false;
                if (name  != null && !name.equals(u.name))      { u.name = name;      ch = true; }
                if (photo != null && !photo.equals(u.photoUrl)) { u.photoUrl = photo; ch = true; }
                if (thumb != null && !thumb.equals(u.thumbUrl)) { u.thumbUrl = thumb; ch = true; }
                if (ch && onlineUsersMap.containsKey(cu)) scheduleOnlineRender();
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void removeOnlineListeners() {
        for (Map.Entry<String, ValueEventListener> entry : onlineListeners.entrySet()) {
            FirebaseUtils.getUserRef(entry.getKey()).child("online").removeEventListener(entry.getValue());
        }
        onlineListeners.clear();
        contactUsers.clear();
        onlineUsersMap.clear();
        onlineViews.clear();
    }

    /** N initial per-contact callbacks → ONE render ~60ms later. */
    private void scheduleOnlineRender() {
        if (onlineRenderPending || rootView == null) return;
        onlineRenderPending = true;
        uiHandler.postDelayed(onlineRenderRunnable, 60L);
    }

    private void ensureOnlinePanel() {
        if (llOnlinePanel != null || stubOnline == null) return;
        View inflated = stubOnline.inflate();
        stubOnline = null;
        llOnlinePanel = (LinearLayout) inflated;
        llOnlineUsers = inflated.findViewById(R.id.ll_online_users);
    }

    /** Diff render: only add/remove the changed users, update name/avatar in place, never re-inflate the rest. */
    private void renderOnlineUsers() {
        if (rootView == null || getContext() == null) return;
        if (onlineUsersMap.isEmpty()) {
            if (llOnlinePanel != null && llOnlinePanel.getVisibility() != View.GONE)
                llOnlinePanel.setVisibility(View.GONE);
            return;
        }
        ensureOnlinePanel();
        if (llOnlinePanel == null || llOnlineUsers == null) return;
        if (llOnlinePanel.getVisibility() != View.VISIBLE) llOnlinePanel.setVisibility(View.VISIBLE);

        Iterator<Map.Entry<String, OnlineHolder>> it = onlineViews.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, OnlineHolder> e = it.next();
            if (!onlineUsersMap.containsKey(e.getKey())) {
                llOnlineUsers.removeView(e.getValue().item);
                it.remove();
            }
        }

        LayoutInflater inf = null;
        for (Map.Entry<String, User> e : onlineUsersMap.entrySet()) {
            final String cu = e.getKey();
            User u = e.getValue();
            final OnlineHolder h;
            OnlineHolder existing = onlineViews.get(cu);
            if (existing == null) {
                if (inf == null) inf = LayoutInflater.from(getContext());
                h = new OnlineHolder();
                h.item = inf.inflate(R.layout.item_online_user, llOnlineUsers, false);
                h.iv = h.item.findViewById(R.id.iv_online_avatar);
                h.tv = h.item.findViewById(R.id.tv_online_name);
                // Online avatar tap → open bottom sheet for quick call (reads the live holder.user)
                h.item.setOnClickListener(x -> {
                    User cur = h.user;
                    if (cur == null) return;
                    CallLog fakeLog = new CallLog();
                    fakeLog.partnerUid  = cur.uid;
                    fakeLog.partnerName = cur.name;
                    String photo = (cur.thumbUrl != null && !cur.thumbUrl.isEmpty())
                        ? cur.thumbUrl : cur.photoUrl;
                    showContactBottomSheet(fakeLog, photo);
                });
                onlineViews.put(cu, h);
                llOnlineUsers.addView(h.item);
            } else {
                h = existing;
            }
            h.user = u;
            String name = u.name != null ? u.name : "User";
            if (!name.equals(h.name)) { h.name = name; h.tv.setText(name); }
            String av = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (av == null) av = "";
            if (!av.equals(h.avatar)) {
                h.avatar = av;
                // PERF (deep avatar pipeline parity — see CallAvatarBinder)
                if (!av.isEmpty()) {
                    CallAvatarBinder.bind(getContext(), h.iv, av, 0,
                        CallAvatarBinder.SHEET_TIER, R.drawable.ic_person);
                }
            }
        }
    }

    /**
     * v951: pre-inflate a few flat rows into the RecycledViewPool while the main thread is idle (3 per idle
     * callback), so the first bind after Room delivers data finds ready holders instead of inflating.
     */
    private void warmRowPool(final RecyclerView rv) {
        final int target = 10;
        Looper.myQueue().addIdleHandler(new android.os.MessageQueue.IdleHandler() {
            @Override public boolean queueIdle() {
                CallHistoryAdapter ad = adapter;
                if (rootView == null || ad == null) return false;
                RecyclerView.RecycledViewPool pool = rv.getRecycledViewPool();
                pool.setMaxRecycledViews(0, 14);
                int have = pool.getRecycledViewCount(0);
                for (int i = 0; i < 3 && have < target; i++, have++) {
                    pool.putRecycledView(ad.createViewHolder(rv, 0));
                }
                return have < target;
            }
        });
    }

    // ── Filters + Search ───────────────────────────────────────────────────
    private final View.OnClickListener chipClick = x -> {
        Object t = x.getTag();
        if (t instanceof String) setActiveFilter((String) t);
    };

    private void setupChip(TextView chip, String filter) {
        if (chip == null) return;
        chip.setTag(filter);
        chip.setOnClickListener(chipClick);
        chip.setSelected(filter.equals(activeFilter));      // no-op when unchanged
    }

    private TextView chipFor(String filter) {
        switch (filter) {
            case "missed":   return chipMissed;
            case "contacts": return chipContacts;
            case "nonspam":  return chipNonspam;
            case "spam":     return chipSpam;
            default:         return chipAll;
        }
    }

    /** Only the previously selected + newly selected chip change (state-list bg/text colour); no drawable/colour churn. */
    private void setActiveFilter(String filter) {
        if (filter.equals(activeFilter)) return;
        TextView prev = chipFor(activeFilter);
        activeFilter = filter;
        if (prev != null) prev.setSelected(false);
        TextView cur = chipFor(filter);
        if (cur != null) cur.setSelected(true);
        applyFilter(false);
    }

    private static final class LogDiff extends DiffUtil.Callback {
        final List<CallLog> o, n;
        LogDiff(List<CallLog> o, List<CallLog> n) { this.o = o; this.n = n; }
        @Override public int getOldListSize() { return o.size(); }
        @Override public int getNewListSize() { return n.size(); }
        @Override public boolean areItemsTheSame(int a, int b) {
            CallLog x = o.get(a), y = n.get(b);
            return x.id != null ? x.id.equals(y.id) : x == y;
        }
        @Override public boolean areContentsTheSame(int a, int b) {
            CallLog x = o.get(a), y = n.get(b);
            return Objects.equals(x.partnerUid, y.partnerUid)
                && Objects.equals(x.partnerName, y.partnerName)
                && Objects.equals(x.partnerPhoto, y.partnerPhoto)
                && Objects.equals(x.direction, y.direction)
                && Objects.equals(x.mediaType, y.mediaType)
                && Objects.equals(x.timestamp, y.timestamp)
                && Objects.equals(x.duration, y.duration);
        }
    }

    private static int sigOf(List<CallLog> list) {
        int h = list.size();
        for (CallLog l : list) {
            h = 31 * h + (l.id == null ? 0 : l.id.hashCode());
            h = 31 * h + (l.partnerName == null ? 0 : l.partnerName.hashCode());
            h = 31 * h + (l.partnerPhoto == null ? 0 : l.partnerPhoto.hashCode());
            h = 31 * h + (l.direction == null ? 0 : l.direction.hashCode());
            h = 31 * h + (l.timestamp == null ? 0 : l.timestamp.hashCode());
            h = 31 * h + (l.duration == null ? 0 : l.duration.hashCode());
        }
        return h;
    }

    /**
     * v950: DiffUtil instead of notifyDataSetChanged — a chip tap, keystroke or an identical Firebase
     * re-delivery no longer re-binds / re-draws every visible row; only changed rows are touched.
     */
    private void applyFilter(boolean dataChanged) {
        final String q = searchQuery;
        final String f = activeFilter;
        List<CallLog> next = new ArrayList<>(allLogs.size());
        for (CallLog l : allLogs) {
            if (!q.isEmpty()) {
                String name = l.partnerName != null ? l.partnerName.toLowerCase() : "";
                if (!name.contains(q)) continue;
            }
            String dir = l.direction == null ? "" : l.direction.toLowerCase();
            switch (f) {
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
            next.add(l);
        }
        if (dataChanged) {
            // Batch-warm the verified-badge cache — only when the data changed (not per keystroke/chip).
            List<String> verifyUids = new ArrayList<>(next.size());
            for (CallLog l : next) if (l.partnerUid != null) verifyUids.add(l.partnerUid);
            com.callx.app.utils.VerifiedBadgeUtils.prefetch(verifyUids);
        }
        final List<CallLog> old = new ArrayList<>(logs);
        logs.clear();
        logs.addAll(next);
        if (adapter != null && !(old.isEmpty() && next.isEmpty())) {
            if (old.isEmpty() || next.isEmpty()) adapter.notifyDataSetChanged();
            else DiffUtil.calculateDiff(new LogDiff(old, next), false).dispatchUpdatesTo(adapter);
        }
        setEmptyVisible(next.isEmpty());
    }

    /** Empty state is a ViewStub: inflated only the first time the list is actually empty. */
    private void setEmptyVisible(boolean show) {
        if (show) {
            if (emptyState == null && stubEmpty != null) {
                emptyState = stubEmpty.inflate();
                stubEmpty = null;
            }
            if (emptyState != null && emptyState.getVisibility() != View.VISIBLE)
                emptyState.setVisibility(View.VISIBLE);
        } else if (emptyState != null && emptyState.getVisibility() != View.GONE) {
            emptyState.setVisibility(View.GONE);
        }
    }

    // ── Call Logs — Offline-First (v950: parsing/mapping off main, identical data skipped) ──────────
    private void loadCallLogsFromRoom() {
        if (getContext() == null) return;
        final AppDatabase db = AppDatabase.getInstance(getContext());
        DB_EXEC.execute(() -> {
            List<CallLogEntity> cached = db.callLogDao().getAllCallLogsSync();
            if (cached == null || cached.isEmpty()) return;
            final List<CallLog> roomLogs = new ArrayList<>(cached.size());
            for (CallLogEntity e : cached) {
                CallLog l = new CallLog();
                l.id          = e.id;
                l.partnerUid  = e.partnerUid;
                l.partnerName = e.partnerName;
                l.partnerPhoto = e.partnerPhoto; // FIX #1: Room se photo restore
                l.direction   = e.direction;
                l.mediaType   = e.mediaType;
                l.timestamp   = e.timestamp;
                l.duration    = e.duration;
                roomLogs.add(l);
            }
            final int sig = sigOf(roomLogs);
            uiHandler.post(() -> {
                if (rootView == null || firebaseLogsLoaded || !allLogs.isEmpty()) return;
                allLogs.addAll(roomLogs);
                lastSig = sig;
                applyFilter(true);
            });
        });
    }

    private void loadCallLogsFromFirebase() {
        final String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || callsListener != null) return;
        final android.content.Context appCtx =
            getContext() != null ? getContext().getApplicationContext() : null;
        callsRef = FirebaseUtils.getCallsRef(uid);
        callsListener = new ValueEventListener() {
            @Override public void onDataChange(final DataSnapshot snap) {
                DB_EXEC.execute(() -> {
                    final List<CallLog> parsed = new ArrayList<>();
                    final List<CallLogEntity> toSave = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        CallLog l = c.getValue(CallLog.class);
                        if (l == null) continue;
                        if (l.id == null) l.id = c.getKey();
                        parsed.add(l);
                        CallLogEntity entity = new CallLogEntity();
                        entity.id          = l.id;
                        entity.partnerUid  = l.partnerUid;
                        entity.partnerName = l.partnerName;
                        entity.partnerPhoto = l.partnerPhoto; // FIX #1: avatar Room mein save
                        entity.direction   = l.direction;
                        entity.mediaType   = l.mediaType;
                        entity.timestamp   = l.timestamp;
                        entity.duration    = l.duration;
                        toSave.add(entity);
                    }
                    Collections.sort(parsed, (a, b) -> {
                        long ta = a.timestamp == null ? 0 : a.timestamp;
                        long tb = b.timestamp == null ? 0 : b.timestamp;
                        return Long.compare(tb, ta);
                    });
                    final int sig = sigOf(parsed);
                    Integer prev = lastSig;
                    boolean same = prev != null && prev == sig;
                    if (!same && appCtx != null && !toSave.isEmpty()) {
                        AppDatabase.getInstance(appCtx).callLogDao().insertCallLogs(toSave);
                    }
                    uiHandler.post(() -> {
                        if (rootView == null) return;
                        firebaseLogsLoaded = true;
                        Integer cur = lastSig;
                        if (cur != null && cur == sig) return;      // identical to what's on screen
                        lastSig = sig;
                        allLogs.clear();
                        allLogs.addAll(parsed);
                        applyFilter(true);
                    });
                });
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        callsRef.addValueEventListener(callsListener);
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
        // PERF (deep avatar pipeline parity — see CallAvatarBinder)
        if (resolvedPhoto != null && !resolvedPhoto.isEmpty()) {
            CallAvatarBinder.bind(getContext(), ivAvatar, resolvedPhoto, 0,
                CallAvatarBinder.SHEET_TIER, R.drawable.ic_person);
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
                        showAvatarZoom(ivAvatar, toShow);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(ivAvatar, resolvedPhoto);
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
    private void showAvatarZoom(View sourceView, String photoUrl) {
        if (getContext() == null) return;
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            getContext(), sourceView, photoUrl,
            com.callx.app.calls.R.drawable.ic_person,
            com.callx.app.calls.R.drawable.ic_close);
    }

    // ── Cleanup — remove ALL real-time listeners when the view is destroyed ────
    @Override public void onDestroyView() {
        super.onDestroyView();
        uiHandler.removeCallbacks(searchRunnable);
        uiHandler.removeCallbacks(onlineRenderRunnable);
        onlineRenderPending = false;
        removeOnlineListeners();
        String uid = FirebaseUtils.getCurrentUid();
        if (uid != null && contactsListener != null)
            FirebaseUtils.getContactsRef(uid).removeEventListener(contactsListener);
        contactsListener = null;
        if (callsRef != null && callsListener != null) callsRef.removeEventListener(callsListener);
        callsListener = null;
        callsRef = null;
        rootView = null;
        stubSelection = null; stubOnline = null; stubEmpty = null;
        emptyState = null; llOnlinePanel = null; llOnlineUsers = null;
        llSelectionBar = null; tvSelectedCount = null;
        chipAll = chipMissed = chipContacts = chipNonspam = chipSpam = null;
        etSearch = null;
        adapter = null;
    }

    // ── Selection ──────────────────────────────────────────────────────────
    @Override
    public void onResume() {
        super.onResume();
        // ── Feature: Clear multi-caller grouping when user views Calls tab ──
        // v950: NotificationManager.cancel (binder IPC) + SharedPreferences edit moved off the main thread.
        final android.content.Context app =
            getContext() != null ? getContext().getApplicationContext() : null;
        if (app == null) return;
        DB_EXEC.execute(() -> {
            try {
                android.app.NotificationManager nm =
                    (android.app.NotificationManager) app
                        .getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(com.callx.app.utils.Constants.MISSED_CALLS_SUMMARY_NOTIF_ID);
                }
                com.callx.app.services.IncomingRingService.clearMissedCallersList(app);
            } catch (Exception ignored) {}
        });
    }

    /** Selection bar is a ViewStub: inflated + wired on first long-press only. */
    private LinearLayout ensureSelectionBar() {
        if (llSelectionBar == null && stubSelection != null) {
            View bar = stubSelection.inflate();
            stubSelection = null;
            llSelectionBar = (LinearLayout) bar;
            tvSelectedCount = bar.findViewById(R.id.tv_selected_count);
            bar.findViewById(R.id.btn_cancel_selection_calls).setOnClickListener(x -> {
                if (adapter != null) adapter.clearSelection();
                if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
            });
            bar.findViewById(R.id.btn_select_all_calls).setOnClickListener(x -> {
                if (adapter != null) adapter.selectAll();
                updateSelectionCount();
            });
            bar.findViewById(R.id.btn_delete_selected_calls).setOnClickListener(x ->
                confirmDeleteSelected());
        }
        return llSelectionBar;
    }

    @Override public void onSelectionStarted() {
        ensureSelectionBar();
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
        AlertDialogStyler.showReusableConfirm(requireContext(),
                "delete_selected_call_logs", AlertDialogStyler.DialogSize.DEFAULT,
                "Delete " + count + " call log" + (count > 1 ? "s" : "") + "?",
                "Selected call history will be permanently deleted.",
                "Delete", this::deleteSelected,
                null, null,
                "Cancel");
    }

    private void deleteSelected() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        if (adapter == null) return;
        List<CallLog> selected = adapter.getSelectedItems();
        for (CallLog l : selected)
            if (l.id != null) FirebaseUtils.getCallsRef(uid).child(l.id).removeValue();
        allLogs.removeAll(selected);
        adapter.clearSelection();
        lastSig = null;
        applyFilter(true);
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

}