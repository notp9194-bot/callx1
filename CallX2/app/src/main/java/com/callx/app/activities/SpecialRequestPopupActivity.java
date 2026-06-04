package com.callx.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.views.EmojiRainView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;

public class SpecialRequestPopupActivity extends AppCompatActivity {

    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    // Typewriter
    private Handler uiHandler;
    private Runnable typeRunnable;
    private int typeIndex = 0;

    // Cursor blink
    private Runnable cursorRunnable;
    private boolean cursorVisible = true;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        uiHandler = new Handler(Looper.getMainLooper());

        final String fromUid   = getIntent().getStringExtra("fromUid");
        final String fromName  = getIntent().getStringExtra("fromName") == null
            ? "User" : getIntent().getStringExtra("fromName");
        final String fromPhoto = getIntent().getStringExtra("fromPhoto");
        final String fromThumb = getIntent().getStringExtra("fromThumb");
        final String reqText   = getIntent().getStringExtra("text") == null
            ? "Please unblock me 🙏" : getIntent().getStringExtra("text");

        if (fromUid == null || fromUid.isEmpty()) { finish(); return; }

        com.google.firebase.auth.FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) { finish(); return; }
        final String myUid  = me.getUid();
        final String myName = me.getDisplayName() != null ? me.getDisplayName() : "";

        // 7-day auto-expire check
        FirebaseUtils.db().getReference("specialRequests")
                .child(myUid).child(fromUid).child("ts")
                .get().addOnSuccessListener(tsSnap -> {
                    Long reqTs = tsSnap.exists() ? tsSnap.getValue(Long.class) : null;
                    if (reqTs != null && (System.currentTimeMillis() - reqTs) > SEVEN_DAYS_MS) {
                        FirebaseUtils.db().getReference("permaBlocked")
                                .child(myUid).child(fromUid).setValue(true);
                        FirebaseUtils.db().getReference("specialRequests")
                                .child(myUid).child(fromUid).removeValue();
                        FirebaseUtils.db().getReference("seenRequests")
                                .child(myUid).child(fromUid).removeValue();
                        finish();
                        return;
                    }
                    showSheet(myUid, myName, fromUid, fromName, fromPhoto, fromThumb, reqText);
                });
    }

    private void showSheet(String myUid, String myName,
                           String fromUid, String fromName,
                           String fromPhoto, String fromThumb, String reqText) {

        View sheet = LayoutInflater.from(this)
            .inflate(R.layout.dialog_special_request, null);

        TextView  tvName    = sheet.findViewById(R.id.tv_sp_name);
        TextView  tvText    = sheet.findViewById(R.id.tv_sp_text);
        TextView  tvCursor  = sheet.findViewById(R.id.tv_cursor);
        TextView  tvWaiting = sheet.findViewById(R.id.tv_waiting);
        ImageView iv        = sheet.findViewById(R.id.iv_sp_avatar);
        EmojiRainView rain  = sheet.findViewById(R.id.emoji_rain);
        MaterialButton btnUnblock = sheet.findViewById(R.id.btn_sp_unblock);
        MaterialButton btnLater   = sheet.findViewById(R.id.btn_sp_later);

        tvName.setText(fromName);

        // Avatar load
        String popupAvatar = (fromThumb != null && !fromThumb.isEmpty()) ? fromThumb : fromPhoto;
        if (popupAvatar != null && !popupAvatar.isEmpty()) {
            Glide.with(this).load(popupAvatar).circleCrop().into(iv);
        } else {
            iv.setImageResource(R.drawable.ic_person);
        }

        // ── 1. Start emoji rain ─────────────────────────────────────────
        rain.startRain();

        // ── 2. Shake avatar — repeat every 3.5 seconds ─────────────────
        Runnable shakeLoop = new Runnable() {
            @Override public void run() {
                iv.startAnimation(AnimationUtils.loadAnimation(
                        SpecialRequestPopupActivity.this, R.anim.shake));
                uiHandler.postDelayed(this, 3500);
            }
        };
        uiHandler.postDelayed(shakeLoop, 600);

        // ── 3. Typewriter effect ────────────────────────────────────────
        typeIndex = 0;
        typeRunnable = new Runnable() {
            @Override public void run() {
                if (typeIndex <= reqText.length()) {
                    tvText.setText(reqText.substring(0, typeIndex));
                    typeIndex++;
                    // Slower on punctuation — jaise ruk ruk ke ro raha ho
                    char c = typeIndex > 0 && typeIndex <= reqText.length()
                            ? reqText.charAt(typeIndex - 1) : 0;
                    long delay = (c == '.' || c == ',' || c == '!' || c == '?') ? 220 : 55;
                    uiHandler.postDelayed(this, delay);
                } else {
                    // Typing done — cursor hide, "still waiting" show
                    tvCursor.setVisibility(View.GONE);
                    uiHandler.postDelayed(() ->
                            tvWaiting.setVisibility(View.VISIBLE), 800);
                }
            }
        };
        uiHandler.postDelayed(typeRunnable, 500); // thoda pause before typing starts

        // ── 4. Blinking cursor ──────────────────────────────────────────
        cursorRunnable = new Runnable() {
            @Override public void run() {
                if (tvCursor.getVisibility() == View.GONE) return;
                cursorVisible = !cursorVisible;
                tvCursor.setAlpha(cursorVisible ? 1f : 0f);
                uiHandler.postDelayed(this, 500);
            }
        };
        uiHandler.post(cursorRunnable);

        // ── Bottom sheet ────────────────────────────────────────────────
        final BottomSheetDialog dlg = new BottomSheetDialog(this,
                com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);
        dlg.setOnDismissListener(d -> {
            stopAllAnimations();
            rain.stopRain();
            finish();
        });

        btnUnblock.setOnClickListener(v -> {
            FirebaseUtils.db().getReference("blocked")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("permaBlocked")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("specialRequests")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("seenRequests")
                .child(myUid).child(fromUid).removeValue();

            PushNotify.notifyUnblock(fromUid, myUid, myName);
            dlg.dismiss();
        });

        btnLater.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    private void stopAllAnimations() {
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopAllAnimations();
    }
}
