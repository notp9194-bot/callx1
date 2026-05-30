package com.callx.app.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ReelUserProfileSheet
 *
 * Reusable helper — avatar pe tap karo → same bottom sheet jo Calls tab mein hoti hai,
 * but without call history row.
 *
 * Usage (from any Activity):
 *   ReelUserProfileSheet.show(this, uid, name, photoUrl);
 */
public class ReelUserProfileSheet {

    /** Main entry point — Activity context chahiye (for BottomSheetDialog + startActivity) */
    public static void show(Activity activity, String uid, String name, String photoUrl) {
        if (activity == null || activity.isFinishing() || uid == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(activity,
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(activity)
            .inflate(R.layout.bottom_sheet_reel_user_profile, null);
        sheet.setContentView(sv);

        // ── Views ──────────────────────────────────────────────────────────
        CircleImageView ivAvatar  = sv.findViewById(R.id.iv_avatar_sheet);
        TextView        tvName    = sv.findViewById(R.id.tv_name_sheet);
        TextView        tvStatus  = sv.findViewById(R.id.tv_status_sheet);
        View            onlineDot = sv.findViewById(R.id.view_online_dot_sheet);
        View            btnMsg    = sv.findViewById(R.id.btn_message_sheet);
        View            btnVoice  = sv.findViewById(R.id.btn_voice_call_sheet);
        View            btnVideo  = sv.findViewById(R.id.btn_video_call_sheet);

        View         btnXSheet    = sv.findViewById(R.id.btn_x_sheet);
        View         btnReels     = sv.findViewById(R.id.btn_reels_sheet);
        View         btnYoutube   = sv.findViewById(R.id.btn_youtube_sheet);
        CircleImageView ivAnimX   = sv.findViewById(R.id.iv_anim_x_sheet);
        CircleImageView ivAnimReel= sv.findViewById(R.id.iv_anim_reel_sheet);
        CircleImageView ivAnimYt  = sv.findViewById(R.id.iv_anim_youtube_sheet);

        View layoutXRow      = sv.findViewById(R.id.layout_x_follow_row);
        View layoutReelsRow  = sv.findViewById(R.id.layout_reels_follow_row);
        View layoutYtRow     = sv.findViewById(R.id.layout_youtube_subscribe_row);
        TextView tvXCount    = sv.findViewById(R.id.tv_x_followers_count);
        TextView tvReelsCount= sv.findViewById(R.id.tv_reels_followers_count);
        TextView tvYtCount   = sv.findViewById(R.id.tv_youtube_subs_count);
        Button btnXFollow    = sv.findViewById(R.id.btn_x_follow_action);
        Button btnReelFollow = sv.findViewById(R.id.btn_reels_follow_action);
        Button btnYtSub      = sv.findViewById(R.id.btn_youtube_subscribe_action);

        // ── Name + Avatar (initial) ────────────────────────────────────────
        if (tvName != null) tvName.setText(name != null ? name : "User");
        if (photoUrl != null && !photoUrl.isEmpty() && ivAvatar != null) {
            Glide.with(activity).load(photoUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person).into(ivAvatar);
        }

        // ── Firebase: online status + fresh photo ─────────────────────────
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                if (activity.isFinishing()) return;
                Boolean online = snap.child("online").getValue(Boolean.class);
                if (Boolean.TRUE.equals(online)) {
                    if (onlineDot != null) onlineDot.setVisibility(View.VISIBLE);
                    if (tvStatus  != null) {
                        tvStatus.setText("Online");
                        tvStatus.setTextColor(activity.getResources()
                            .getColor(R.color.brand_accent, null));
                    }
                } else {
                    if (onlineDot != null) onlineDot.setVisibility(View.GONE);
                    if (tvStatus  != null) {
                        tvStatus.setText("Offline");
                        tvStatus.setTextColor(activity.getResources()
                            .getColor(R.color.text_muted, null));
                    }
                }
                String photo2 = snap.child("photoUrl").getValue(String.class);
                String thumb2 = snap.child("thumbUrl").getValue(String.class);
                String url    = (thumb2 != null && !thumb2.isEmpty()) ? thumb2 : photo2;
                if (url != null && !url.isEmpty() && ivAvatar != null)
                    Glide.with(activity).load(url)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });

        // ── Avatar tap → full-screen zoom ─────────────────────────────────
        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(x -> {
                FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String full = snap.child("photoUrl").getValue(String.class);
                        showAvatarZoom(activity,
                            (full != null && !full.isEmpty()) ? full : photoUrl);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(activity, photoUrl);
                    }
                });
            });
        }

        // ── Message button ─────────────────────────────────────────────────
        if (btnMsg != null) {
            btnMsg.setOnClickListener(x -> {
                sheet.dismiss();
                Intent i = new Intent()
                    .setClassName(activity.getPackageName(), "com.callx.app.activities.ChatActivity");
                i.putExtra("partnerUid",   uid);
                i.putExtra("partnerName",  name != null ? name : "");
                i.putExtra("partnerPhoto", photoUrl != null ? photoUrl : "");
                activity.startActivity(i);
            });
        }

        // ── Voice Call ─────────────────────────────────────────────────────
        if (btnVoice != null) {
            btnVoice.setOnClickListener(x -> {
                sheet.dismiss();
                Intent i = new Intent()
                    .setClassName(activity.getPackageName(), "com.callx.app.activities.CallActivity");
                i.putExtra("partnerUid",  uid);
                i.putExtra("partnerName", name != null ? name : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                activity.startActivity(i);
            });
        }

        // ── Video Call ─────────────────────────────────────────────────────
        if (btnVideo != null) {
            btnVideo.setOnClickListener(x -> {
                sheet.dismiss();
                Intent i = new Intent()
                    .setClassName(activity.getPackageName(), "com.callx.app.activities.CallActivity");
                i.putExtra("partnerUid",  uid);
                i.putExtra("partnerName", name != null ? name : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                activity.startActivity(i);
            });
        }

        // ── Social buttons ─────────────────────────────────────────────────
        CircleImageView[] peekViews   = {ivAnimX, ivAnimReel, ivAnimYt};
        Handler[]        animHandler  = {new Handler(Looper.getMainLooper())};
        boolean[]        animRunning  = {false};
        Runnable[]       animRunnable = {null};

        loadSocialButtons(activity, uid, sheet,
            btnXSheet, btnReels, btnYoutube,
            ivAnimX, ivAnimReel, ivAnimYt,
            layoutXRow, layoutReelsRow, layoutYtRow,
            tvXCount, tvReelsCount, tvYtCount,
            btnXFollow, btnReelFollow, btnYtSub,
            peekViews, animHandler, animRunning, animRunnable);

        sheet.setOnDismissListener(d -> {
            animRunning[0] = false;
            if (animRunnable[0] != null) animHandler[0].removeCallbacks(animRunnable[0]);
            for (CircleImageView iv : peekViews) {
                if (iv != null) {
                    iv.setVisibility(View.INVISIBLE);
                    iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
                }
            }
        });

        sheet.show();
    }

    // ── Social buttons loader ──────────────────────────────────────────────

    private static void loadSocialButtons(
            Activity activity, String partnerUid, BottomSheetDialog sheet,
            View btnXSheet, View btnReels, View btnYoutube,
            CircleImageView ivAnimX, CircleImageView ivAnimReel, CircleImageView ivAnimYt,
            View layoutXRow, View layoutReelsRow, View layoutYtRow,
            TextView tvXCount, TextView tvReelsCount, TextView tvYtCount,
            Button btnXFollow, Button btnReelFollow, Button btnYtSub,
            CircleImageView[] peekViews,
            Handler[] animHandler, boolean[] animRunning, Runnable[] animRunnable) {

        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        final FirebaseDatabase db = FirebaseDatabase.getInstance(DB);
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        // ── X ──
        db.getReference("x/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (activity.isFinishing() || !snap.exists()) return;
                    String xPhoto = snap.child("photoUrl").getValue(String.class);
                    if (xPhoto != null && !xPhoto.isEmpty() && ivAnimX != null)
                        Glide.with(activity).load(xPhoto).circleCrop()
                            .placeholder(R.drawable.ic_person).into(ivAnimX);
                    startPeekLoop(peekViews, animHandler, animRunning, animRunnable, activity);

                    Long xF = snap.child("followerCount").getValue(Long.class);
                    long cnt = xF != null ? xF : 0;
                    if (tvXCount  != null) tvXCount.setText(formatCount(cnt) + " Followers");
                    if (layoutXRow!= null) layoutXRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnXFollow != null) {
                        db.getReference("x/followers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isF = {ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class))};
                                    setXBtn(btnXFollow, isF[0]);
                                    btnXFollow.setOnClickListener(v -> {
                                        isF[0] = !isF[0]; setXBtn(btnXFollow, isF[0]);
                                        if (isF[0]) {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("x/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvXCount != null) bumpCount(tvXCount, 1, "Followers");
                                        } else {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("x/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvXCount != null) bumpCount(tvXCount, -1, "Followers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    if (btnXSheet != null) {
                        btnXSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            try {
                                Class<?> cls = Class.forName("com.callx.app.activities.XProfileSheet");
                                java.lang.reflect.Method m = cls.getMethod("showProfile",
                                    androidx.fragment.app.FragmentManager.class, String.class);
                                m.invoke(null, ((androidx.fragment.app.FragmentActivity) activity)
                                    .getSupportFragmentManager(), partnerUid);
                            } catch (Exception ex) {
                                Toast.makeText(activity, "X profile not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── Reels ──
        db.getReference("reels/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (activity.isFinishing() || !snap.exists()) return;
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String rp    = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (rp != null && !rp.isEmpty() && ivAnimReel != null)
                        Glide.with(activity).load(rp).circleCrop()
                            .placeholder(R.drawable.ic_person).into(ivAnimReel);

                    db.getReference("reels/followers").child(partnerUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot fSnap) {
                                long fCnt = fSnap.getChildrenCount();
                                if (tvReelsCount  != null) tvReelsCount.setText(formatCount(fCnt) + " Followers");
                                if (layoutReelsRow!= null) layoutReelsRow.setVisibility(View.VISIBLE);
                                if (myUid != null && btnReelFollow != null) {
                                    boolean[] isF = {fSnap.hasChild(myUid)};
                                    setReelsBtn(btnReelFollow, isF[0]);
                                    btnReelFollow.setOnClickListener(v -> {
                                        isF[0] = !isF[0]; setReelsBtn(btnReelFollow, isF[0]);
                                        if (isF[0]) {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("reels/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvReelsCount != null) bumpCount(tvReelsCount, 1, "Followers");
                                        } else {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("reels/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvReelsCount != null) bumpCount(tvReelsCount, -1, "Followers");
                                        }
                                    });
                                }
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });

                    if (btnReels != null) {
                        btnReels.setOnClickListener(v -> {
                            sheet.dismiss();
                            try {
                                Intent i = new Intent(activity,
                                    Class.forName("com.callx.app.activities.UserReelsActivity"));
                                i.putExtra("uid", partnerUid);
                                activity.startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(activity, "Reels not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── YouTube ──
        db.getReference("youtube/channels").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (activity.isFinishing() || !snap.exists()) return;
                    String yt = snap.child("thumbUrl").getValue(String.class);
                    String yp = snap.child("photoUrl").getValue(String.class);
                    String ya = (yt != null && !yt.isEmpty()) ? yt : yp;
                    if (ya != null && !ya.isEmpty() && ivAnimYt != null)
                        Glide.with(activity).load(ya).circleCrop()
                            .placeholder(R.drawable.ic_person).into(ivAnimYt);

                    Long subC = snap.child("subscriberCount").getValue(Long.class);
                    long subs = subC != null ? subC : 0;
                    if (tvYtCount  != null) tvYtCount.setText(formatCount(subs) + " Subscribers");
                    if (layoutYtRow!= null) layoutYtRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnYtSub != null) {
                        db.getReference("youtube/subscribers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isS = {ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class))};
                                    setYtBtn(btnYtSub, isS[0]);
                                    btnYtSub.setOnClickListener(v -> {
                                        isS[0] = !isS[0]; setYtBtn(btnYtSub, isS[0]);
                                        if (isS[0]) {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).setValue(true);
                                            if (tvYtCount != null) bumpCount(tvYtCount, 1, "Subscribers");
                                        } else {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).removeValue();
                                            if (tvYtCount != null) bumpCount(tvYtCount, -1, "Subscribers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    if (btnYoutube != null) {
                        btnYoutube.setOnClickListener(v -> {
                            sheet.dismiss();
                            try {
                                Intent i = new Intent(activity,
                                    Class.forName("com.callx.app.activities.YouTubeChannelActivity"));
                                i.putExtra("uid", partnerUid);
                                activity.startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(activity, "YouTube not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── Avatar peek animation (same as ChatsFragment) ─────────────────────

    private static void startPeekLoop(
            CircleImageView[] views, Handler[] handlerArr,
            boolean[] runningArr, Runnable[] runnableArr, Activity activity) {
        if (runningArr[0]) return;
        runningArr[0] = true;
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
        }
        runnableArr[0] = new Runnable() {
            int idx = 0;
            @Override public void run() {
                if (!runningArr[0] || activity.isFinishing()) return;
                CircleImageView iv = views[idx % views.length]; idx++;
                if (iv == null) { handlerArr[0].postDelayed(this, 500); return; }
                iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
                iv.setVisibility(View.VISIBLE);
                ObjectAnimator sxI = ObjectAnimator.ofFloat(iv, "scaleX", 0f, 1.05f, 1.0f);
                ObjectAnimator syI = ObjectAnimator.ofFloat(iv, "scaleY", 0f, 1.05f, 1.0f);
                ObjectAnimator aI  = ObjectAnimator.ofFloat(iv, "alpha",  0f, 1f);
                sxI.setDuration(450); syI.setDuration(450); aI.setDuration(250);
                AnimatorSet zIn = new AnimatorSet(); zIn.playTogether(sxI, syI, aI);
                ObjectAnimator sxO = ObjectAnimator.ofFloat(iv, "scaleX", 1.0f, 0f);
                ObjectAnimator syO = ObjectAnimator.ofFloat(iv, "scaleY", 1.0f, 0f);
                ObjectAnimator aO  = ObjectAnimator.ofFloat(iv, "alpha",  1f, 0f);
                sxO.setDuration(400); syO.setDuration(400); aO.setDuration(400);
                AnimatorSet zOut = new AnimatorSet(); zOut.playTogether(sxO, syO, aO);
                zOut.setStartDelay(3000);
                AnimatorSet full = new AnimatorSet(); full.playSequentially(zIn, zOut);
                final Runnable me = this;
                full.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
                        if (runningArr[0] && !activity.isFinishing())
                            handlerArr[0].postDelayed(me, 3000);
                    }
                });
                full.start();
            }
        };
        handlerArr[0].postDelayed(runnableArr[0], 1500);
    }

    // ── Avatar full-screen zoom ────────────────────────────────────────────

    private static void showAvatarZoom(Activity activity, String photoUrl) {
        if (activity == null || activity.isFinishing()) return;
        android.app.Dialog dialog = new android.app.Dialog(
            activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.FrameLayout root = new android.widget.FrameLayout(activity);
        root.setBackgroundColor(0xEE000000);

        com.github.chrisbanes.photoview.PhotoView pv =
            new com.github.chrisbanes.photoview.PhotoView(activity);
        android.widget.FrameLayout.LayoutParams pvLp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        pv.setLayoutParams(pvLp);
        pv.setMinimumScale(1f); pv.setMediumScale(2f); pv.setMaximumScale(5f);
        pv.setOnOutsidePhotoTapListener(v -> dialog.dismiss());

        android.widget.ImageButton btnClose = new android.widget.ImageButton(activity);
        float dp = activity.getResources().getDisplayMetrics().density;
        int sz = (int)(40 * dp);
        android.widget.FrameLayout.LayoutParams clp =
            new android.widget.FrameLayout.LayoutParams(sz, sz);
        clp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        clp.topMargin = (int)(40 * dp); clp.rightMargin = (int)(16 * dp);
        btnClose.setLayoutParams(clp);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        if (photoUrl != null && !photoUrl.isEmpty())
            Glide.with(activity).load(photoUrl)
                .placeholder(R.drawable.ic_person).error(R.drawable.ic_person).into(pv);
        else pv.setImageResource(R.drawable.ic_person);

        root.addView(pv); root.addView(btnClose);
        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    // ── Button state helpers ───────────────────────────────────────────────

    private static void setXBtn(Button btn, boolean following) {
        if (btn == null) return;
        btn.setText(following ? "Following" : "Follow");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            following ? 0xFF333333 : 0xFF000000));
    }

    private static void setReelsBtn(Button btn, boolean following) {
        if (btn == null) return;
        btn.setText(following ? "Following" : "Follow");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            following ? 0xFF555555 : 0xFFDD2A7B));
    }

    private static void setYtBtn(Button btn, boolean subscribed) {
        if (btn == null) return;
        btn.setText(subscribed ? "Subscribed" : "Subscribe");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            subscribed ? 0xFF333333 : 0xFFFF0000));
    }

    private static void bumpCount(TextView tv, int delta, String label) {
        try {
            String raw = tv.getText().toString().split(" ")[0]
                .replace("K", "000").replace("M", "000000");
            long cur = Long.parseLong(raw);
            tv.setText(formatCount(Math.max(0, cur + delta)) + " " + label);
        } catch (Exception ignored) {}
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
