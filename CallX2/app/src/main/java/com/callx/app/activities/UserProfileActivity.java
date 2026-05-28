package com.callx.app.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.databinding.ActivityUserProfileBinding;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * UserProfileActivity — Chat header avatar click ya 3-dot "View Profile" se open hoti hai.
 *
 * Intent extras:
 *   "uid"       — partner ka UID (required)
 *   "name"      — partner ka naam (fallback, Firebase se refresh hoga)
 *   "photo"     — partner ka full photo URL (fallback)
 *   "chatId"    — back-to-chat ke liye (optional)
 */
public class UserProfileActivity extends AppCompatActivity {

    private ActivityUserProfileBinding binding;

    // Partner data
    private String partnerUid;
    private String partnerName;
    private String partnerPhoto;
    private String chatId;

    // Mute state (sync from ChatActivity isMuted logic)
    private boolean isMuted = false;
    private boolean isBlocked = false;

    // ── Avatar peek animation fields ──────────────────────────────────────
    private CircleImageView ivAnimReel, ivAnimX, ivAnimYoutube;
    private final Handler   animHandler = new Handler(Looper.getMainLooper());
    private Runnable        animRunnable;
    private boolean         animRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUserProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // ── Intent extras ──────────────────────────────────────────────
        partnerUid   = getIntent().getStringExtra("uid");
        partnerName  = getIntent().getStringExtra("name");
        partnerPhoto = getIntent().getStringExtra("photo");
        chatId       = getIntent().getStringExtra("chatId");

        if (partnerUid == null || partnerUid.isEmpty()) {
            Toast.makeText(this, "Profile load nahi ho saka", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // ── Toolbar setup ───────────────────────────────────────────────
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Collapsing toolbar title = partner name
        if (partnerName != null && !partnerName.isEmpty()) {
            binding.collapsingToolbar.setTitle(partnerName);
        }

        // ── Avatar: fast load from intent photo ────────────────────────
        if (partnerPhoto != null && !partnerPhoto.isEmpty()) {
            Glide.with(this)
                .load(partnerPhoto)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(binding.ivAvatarLarge);
        }

        // Avatar click → fullscreen zoom
        binding.ivAvatarLarge.setOnClickListener(v -> showAvatarZoom(partnerPhoto));

        // ── Action buttons ─────────────────────────────────────────────
        binding.btnActionReel.setOnClickListener(v -> openUserReels());
        binding.btnActionX.setOnClickListener(v -> openUserX());
        binding.btnActionYoutube.setOnClickListener(v -> openUserYoutube());
        binding.btnActionVoice.setOnClickListener(v -> startCall(false));
        binding.btnActionVideo.setOnClickListener(v -> startCall(true));

        // ── Avatar anim views ──────────────────────────────────────────
        ivAnimReel    = binding.ivAnimReel;
        ivAnimX       = binding.ivAnimX;
        ivAnimYoutube = binding.ivAnimYoutube;

        // ── Options ────────────────────────────────────────────────────
        binding.btnMute.setOnClickListener(v -> toggleMute());
        binding.btnBlock.setOnClickListener(v -> confirmBlock());
        binding.btnReport.setOnClickListener(v -> confirmReport());

        // ── Load fresh data from Firebase ──────────────────────────────
        loadProfile();
        loadMuteState();
        loadBlockState();
        loadAvatarAndStartAnimation();
    }

    // ──────────────────────────────────────────────────────────────────────
    // MUTE + BLOCK STATE LOAD
    // ──────────────────────────────────────────────────────────────────────

    private void loadMuteState() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) return;
        FirebaseUtils.db().getReference("muted")
            .child(myUid).child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    isMuted = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    updateMuteLabel();
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void loadBlockState() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) return;
        FirebaseUtils.db().getReference("blocked")
            .child(myUid).child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    isBlocked = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    binding.tvBlockLabel.setText(isBlocked ? "🚫  Unblock" : "🚫  Block");
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ──────────────────────────────────────────────────────────────────────
    // FIREBASE LOAD
    // ──────────────────────────────────────────────────────────────────────

    private void loadProfile() {
        FirebaseUtils.getUserRef(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot s) {
                    String name      = orEmpty(s.child("name").getValue(String.class));
                    String bio       = orEmpty(s.child("bio").getValue(String.class));
                    String about     = orEmpty(s.child("about").getValue(String.class));
                    String phone     = orEmpty(s.child("phone").getValue(String.class));
                    String whatsapp  = orEmpty(s.child("whatsapp").getValue(String.class));
                    String instagram = orEmpty(s.child("instagram").getValue(String.class));
                    String otherLink = orEmpty(s.child("otherLink").getValue(String.class));
                    String callxId   = orEmpty(s.child("callxId").getValue(String.class));
                    String photo     = orEmpty(s.child("photoUrl").getValue(String.class));
                    String thumb     = orEmpty(s.child("thumbUrl").getValue(String.class));

                    // Update stored photo
                    if (!photo.isEmpty()) partnerPhoto = photo;
                    if (!name.isEmpty())  partnerName  = name;

                    // Name + collapsing title
                    binding.tvName.setText(name.isEmpty() ? orEmpty(partnerName) : name);
                    binding.collapsingToolbar.setTitle(name.isEmpty() ? orEmpty(partnerName) : name);

                    // CallX ID
                    if (!callxId.isEmpty()) {
                        binding.tvCallxId.setText("@" + callxId);
                    }

                    // Bio
                    if (!bio.isEmpty()) {
                        binding.tvBio.setText(bio);
                        binding.tvBio.setVisibility(View.VISIBLE);
                    }

                    // About
                    if (!about.isEmpty()) {
                        binding.tvAbout.setText(about);
                        binding.cardAbout.setVisibility(View.VISIBLE);
                    }

                    // Phone
                    if (!phone.isEmpty()) {
                        binding.tvPhone.setText(phone);
                        binding.cardPhone.setVisibility(View.VISIBLE);
                    }

                    // Social links
                    boolean hasSocial = false;
                    if (!whatsapp.isEmpty()) {
                        binding.tvWhatsapp.setText(whatsapp);
                        binding.rowWhatsapp.setVisibility(View.VISIBLE);
                        binding.rowWhatsapp.setOnClickListener(v -> openWhatsApp(whatsapp));
                        hasSocial = true;
                    }
                    if (!instagram.isEmpty()) {
                        binding.tvInstagram.setText("@" + instagram.replace("@", ""));
                        binding.rowInstagram.setVisibility(View.VISIBLE);
                        binding.rowInstagram.setOnClickListener(v -> openInstagram(instagram));
                        hasSocial = true;
                    }
                    if (!otherLink.isEmpty()) {
                        binding.tvOtherLink.setText(otherLink);
                        binding.rowOtherLink.setVisibility(View.VISIBLE);
                        binding.rowOtherLink.setOnClickListener(v -> openUrl(otherLink));
                        hasSocial = true;
                    }
                    if (hasSocial) binding.cardSocial.setVisibility(View.VISIBLE);

                    // Avatar — prefer full photo, fallback thumb
                    String displayUrl = !photo.isEmpty() ? photo : (!thumb.isEmpty() ? thumb : "");
                    if (!displayUrl.isEmpty()) {
                        Glide.with(UserProfileActivity.this)
                            .load(displayUrl)
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(binding.ivAvatarLarge);
                    }

                    // Mute state from Firebase (same path as ChatActivity: /muted/{myUid}/{partnerUid})
                    // We load it separately after profile load
                }

                @Override
                public void onCancelled(DatabaseError e) {}
            });
    }

    // ──────────────────────────────────────────────────────────────────────
    // ACTIONS
    // ──────────────────────────────────────────────────────────────────────

    /** Open 1-1 chat with this user */
    private void openChat() {
        try {
            // ChatActivity is in feature-chat module
            Intent i = new Intent().setClassName(this, "com.callx.app.activities.ChatActivity");
            i.putExtra("partnerUid",   partnerUid);
            i.putExtra("partnerName",  partnerName  != null ? partnerName  : "");
            i.putExtra("partnerPhoto", partnerPhoto != null ? partnerPhoto : "");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Chat open nahi ho saka", Toast.LENGTH_SHORT).show();
        }
    }

    /** Start voice or video call */
    private void startCall(boolean isVideo) {
        try {
            Intent i = new Intent().setClassName(this, "com.callx.app.activities.CallActivity");
            i.putExtra("partnerUid",   partnerUid);
            i.putExtra("partnerName",  partnerName  != null ? partnerName  : "");
            i.putExtra("partnerPhoto", partnerPhoto != null ? partnerPhoto : "");
            i.putExtra("isCaller",     true);
            i.putExtra("video",        isVideo);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this,
                (isVideo ? "Video" : "Voice") + " call shuru nahi ho saka",
                Toast.LENGTH_SHORT).show();
        }
    }

    /** Return to chat in search mode */
    private void openChatSearch() {
        try {
            Intent i = new Intent().setClassName(this, "com.callx.app.activities.ChatActivity");
            i.putExtra("partnerUid",   partnerUid);
            i.putExtra("partnerName",  partnerName  != null ? partnerName  : "");
            i.putExtra("partnerPhoto", partnerPhoto != null ? partnerPhoto : "");
            i.putExtra("openSearch",   true);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Search open nahi ho saka", Toast.LENGTH_SHORT).show();
        }
    }

    /** Toggle mute for this conversation — same path as ChatActivity */
    private void toggleMute() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) return;
        isMuted = !isMuted;
        updateMuteLabel();
        FirebaseUtils.db().getReference("muted")
            .child(myUid).child(partnerUid)
            .setValue(isMuted ? true : null);
        Toast.makeText(this,
            isMuted ? "Notifications mute ho gayi" : "Notifications unmute ho gayi",
            Toast.LENGTH_SHORT).show();
    }

    private void updateMuteLabel() {
        binding.tvMuteLabel.setText(isMuted ? "🔔  Unmute Notifications" : "🔔  Mute Notifications");
    }

    /** Block confirmation dialog */
    private void confirmBlock() {
        String msg = (partnerName != null && !partnerName.isEmpty())
            ? partnerName + " ko block karna chahte ho?"
            : "Is user ko block karna chahte ho?";
        new AlertDialog.Builder(this)
            .setTitle(isBlocked ? "Unblock" : "Block")
            .setMessage(msg)
            .setPositiveButton(isBlocked ? "Unblock" : "Block", (d, w) -> blockUser())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void blockUser() {
        isBlocked = !isBlocked;
        String myUid = FirebaseUtils.getCurrentUid();
        FirebaseUtils.db().getReference("blocked")
            .child(myUid).child(partnerUid)
            .setValue(isBlocked ? true : null);
        if (isBlocked) {
            binding.tvBlockLabel.setText("🚫  Unblock");
            Toast.makeText(this, "User block ho gaya", Toast.LENGTH_SHORT).show();
        } else {
            binding.tvBlockLabel.setText("🚫  Block");
            Toast.makeText(this, "User unblock ho gaya", Toast.LENGTH_SHORT).show();
        }
    }

    /** Report user dialog */
    private void confirmReport() {
        String[] reasons = {
            "Spam ya unwanted messages",
            "Inappropriate content",
            "Harassment ya bullying",
            "Fake account",
            "Kuch aur"
        };
        new AlertDialog.Builder(this)
            .setTitle("Report karo")
            .setItems(reasons, (d, which) -> {
                Toast.makeText(this, "Report submit ho gayi", Toast.LENGTH_SHORT).show();
                // TODO: Firebase mein report log karo
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ──────────────────────────────────────────────────────────────────────
    // SOCIAL LINK OPENERS
    // ──────────────────────────────────────────────────────────────────────

    private void openWhatsApp(String number) {
        String clean = number.replaceAll("[^0-9+]", "");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/" + clean.replace("+", "")));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "WhatsApp nahi mila", Toast.LENGTH_SHORT).show();
        }
    }

    private void openInstagram(String handle) {
        String clean = handle.replace("@", "");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://instagram.com/" + clean));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Instagram open nahi ho saka", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            if (!url.startsWith("http")) url = "https://" + url;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Link open nahi ho saka", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUserReels() {
        // UserReelsActivity — exact same as Reels profile header mein click
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.UserReelsActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("uid",   partnerUid);
            i.putExtra("name",  orEmpty(partnerName));
            i.putExtra("photo", orEmpty(partnerPhoto));
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Reel profile not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUserX() {
        // XProfileSheet.showProfile() — same as Reels X button
        if (partnerUid == null || partnerUid.isEmpty()) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.XProfileSheet");
            java.lang.reflect.Method method = cls.getMethod(
                    "showProfile",
                    androidx.fragment.app.FragmentManager.class,
                    String.class);
            method.invoke(null, getSupportFragmentManager(), partnerUid);
        } catch (Exception e) {
            Toast.makeText(this, "X profile not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUserYoutube() {
        // YouTubeChannelActivity — same as Reels YouTube button
        if (partnerUid == null || partnerUid.isEmpty()) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.YouTubeChannelActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("uid",  partnerUid);
            i.putExtra("name", orEmpty(partnerName));
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "YouTube channel not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // AVATAR FULLSCREEN ZOOM
    // ──────────────────────────────────────────────────────────────────────

    private void showAvatarZoom(String photoUrl) {
        android.app.Dialog dialog = new android.app.Dialog(
            this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xEE000000);

        com.github.chrisbanes.photoview.PhotoView photoView =
            new com.github.chrisbanes.photoview.PhotoView(this);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f);
        photoView.setMediumScale(2f);
        photoView.setMaximumScale(5f);
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());

        ImageButton btnClose = new ImageButton(this);
        int sz = (int)(40 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(sz, sz);
        closeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        closeLp.topMargin  = (int)(40 * getResources().getDisplayMetrics().density);
        closeLp.rightMargin = (int)(16 * getResources().getDisplayMetrics().density);
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(this).load(photoUrl)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(photoView);
        } else {
            photoView.setImageResource(R.drawable.ic_person);
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

    // ──────────────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────────────

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ──────────────────────────────────────────────────────────────────────
    // AVATAR PEEK ANIMATION — same as UserReelsActivity
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 5 button avatars load karta hai:
     *   Reel    → reels/users/{uid}
     *   X       → x/users/{uid}
     *   YouTube → youtube/channels/{uid}
     *   Call    → users/{uid}  (main CallX profile)
     *   Video   → users/{uid}  (same as Call)
     */
    private void loadAvatarAndStartAnimation() {
        if (partnerUid == null || partnerUid.isEmpty()) return;

        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";

        // 1) Reel avatar — reels/users/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("reels/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimReel == null) { startAvatarPeekLoop(); return; }
                    if (url != null) {
                        Glide.with(UserProfileActivity.this).load(url).circleCrop()
                            .placeholder(R.drawable.ic_person).into(ivAnimReel);
                    }
                    startAvatarPeekLoop();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    startAvatarPeekLoop();
                }
            });

        // 2) X avatar — x/users/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("x/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimX == null || url == null) return;
                    Glide.with(UserProfileActivity.this).load(url).circleCrop()
                        .placeholder(R.drawable.ic_person).into(ivAnimX);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // 3) YouTube avatar — youtube/channels/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("youtube/channels").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimYoutube == null || url == null) return;
                    Glide.with(UserProfileActivity.this).load(url).circleCrop()
                        .placeholder(R.drawable.ic_person).into(ivAnimYoutube);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

    }

    /**
     * Loop: Reel → X → YouTube → Call → Video → Reel → ...
     * Each: peek out (450ms) → hold 3s → peek in (400ms) → wait 3s → next
     */
    private void startAvatarPeekLoop() {
        if (animRunning) return;
        animRunning = true;

        CircleImageView[] views = {ivAnimReel, ivAnimX, ivAnimYoutube};

        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }

        animRunnable = new Runnable() {
            int idx = 0;

            @Override public void run() {
                if (!animRunning || isFinishing() || isDestroyed()) return;

                CircleImageView iv = views[idx % views.length];
                idx++;

                if (iv == null) {
                    animHandler.postDelayed(this, 500);
                    return;
                }

                iv.setScaleX(0f);
                iv.setScaleY(0f);
                iv.setAlpha(0f);
                iv.setVisibility(View.VISIBLE);

                // Zoom IN: 0 → 1.35 overshoot → settle 1.2
                ObjectAnimator scaleXIn = ObjectAnimator.ofFloat(iv, "scaleX", 0f, 1.35f, 1.2f);
                ObjectAnimator scaleYIn = ObjectAnimator.ofFloat(iv, "scaleY", 0f, 1.35f, 1.2f);
                ObjectAnimator alphaIn  = ObjectAnimator.ofFloat(iv, "alpha",  0f, 1f);
                scaleXIn.setDuration(450);
                scaleYIn.setDuration(450);
                alphaIn.setDuration(250);
                scaleXIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
                scaleYIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
                AnimatorSet zoomIn = new AnimatorSet();
                zoomIn.playTogether(scaleXIn, scaleYIn, alphaIn);

                // Zoom OUT: 1.2 → 0 (after 3s hold)
                ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(iv, "scaleX", 1.2f, 0f);
                ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(iv, "scaleY", 1.2f, 0f);
                ObjectAnimator alphaOut  = ObjectAnimator.ofFloat(iv, "alpha",  1f, 0f);
                scaleXOut.setDuration(400);
                scaleYOut.setDuration(400);
                alphaOut.setDuration(400);
                scaleXOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
                scaleYOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
                AnimatorSet zoomOut = new AnimatorSet();
                zoomOut.playTogether(scaleXOut, scaleYOut, alphaOut);
                zoomOut.setStartDelay(3000);

                AnimatorSet full = new AnimatorSet();
                full.playSequentially(zoomIn, zoomOut);
                full.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f);
                        iv.setScaleY(0f);
                        iv.setAlpha(0f);
                        if (animRunning && !isFinishing() && !isDestroyed())
                            animHandler.postDelayed(animRunnable, 3000);
                    }
                });
                full.start();
            }
        };

        animHandler.postDelayed(animRunnable, 1500);
    }

    private void stopAvatarAnimation() {
        animRunning = false;
        if (animRunnable != null) animHandler.removeCallbacks(animRunnable);
        CircleImageView[] views = {ivAnimReel, ivAnimX, ivAnimYoutube};
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }
    }

    @Override protected void onPause()   { super.onPause();   stopAvatarAnimation(); }
    @Override protected void onResume()  { super.onResume();  if (partnerUid != null) loadAvatarAndStartAnimation(); }
    @Override protected void onDestroy() { super.onDestroy(); stopAvatarAnimation(); }
}
