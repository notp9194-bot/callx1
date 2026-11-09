package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;
import com.google.firebase.database.ValueEventListener;

import java.util.Locale;

/**
 * v790 — Screen 5 "Request Sent / Permission Status". Live-watches remixRequests/{owner}/{id}:
 * pending (48 h countdown) → approved (Start) / rejected / expired (Send again) / cancelled.
 */
public class RemixRequestStatusActivity extends AppCompatActivity {

    private static final int PURPLE = 0xFF6B46F6, RED = 0xFFE5484D, GREEN = 0xFF22A06B, GREY = 0xFF6E6E85;

    private RemixCollabTarget target;
    private String mode = RemixCollabTarget.MODE_REMIX;
    private String requestId;
    private ValueEventListener listener;
    private CountDownTimer timer;
    private String lastStatus = "";

    private ImageView ivState;
    private TextView tvTitle, tvMessage, tvExpiry, tvExpiryLabel, btnSecondary;
    private View cardExpiry;
    private Button btnPrimary;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remix_request_status);
        target = RemixCollabTarget.from(getIntent());
        if (RemixCollabTarget.MODE_COLLAB.equals(getIntent().getStringExtra(RemixCollabTarget.X_MODE)))
            mode = RemixCollabTarget.MODE_COLLAB;
        requestId = getIntent().getStringExtra(RemixCollabTarget.X_REQUEST_ID);
        if (!target.isValid() || requestId == null || requestId.isEmpty()) { finish(); return; }

        ivState       = findViewById(R.id.iv_state);
        tvTitle       = findViewById(R.id.tv_title);
        tvMessage     = findViewById(R.id.tv_message);
        tvExpiry      = findViewById(R.id.tv_expiry);
        tvExpiryLabel = findViewById(R.id.tv_expiry_label);
        cardExpiry    = findViewById(R.id.card_expiry);
        btnPrimary    = findViewById(R.id.btn_primary);
        btnSecondary  = findViewById(R.id.btn_secondary);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        listener = RemixRequestHelper.watch(target.ownerUid, requestId, this::render);
    }

    @Override
    protected void onDestroy() {
        if (listener != null) RemixRequestHelper.unwatch(target.ownerUid, requestId, listener);
        if (timer != null) timer.cancel();
        super.onDestroy();
    }

    private String what() { return RemixCollabTarget.MODE_COLLAB.equals(mode) ? "Collab" : "Remix"; }
    private String who()  { return target.ownerName.isEmpty() ? "the creator" : "@" + target.ownerName; }

    private void render(@NonNull String status, long expiresAt) {
        if (isFinishing() || isDestroyed()) return;
        if (status.equals(lastStatus) && RemixRequestHelper.PENDING.equals(status)) return; // countdown keeps running
        lastStatus = status;
        if (timer != null) { timer.cancel(); timer = null; }
        cardExpiry.setVisibility(View.GONE);
        btnSecondary.setVisibility(View.VISIBLE);

        switch (status) {
            case RemixRequestHelper.APPROVED:
                icon(R.drawable.rc_ic_check, GREEN);
                tvTitle.setText("Approved! 🎉");
                tvMessage.setText(Character.toUpperCase(who().charAt(0)) + who().substring(1)
                        + " allowed your " + what() + ". Go create something great.");
                btnPrimary.setText("Start " + what());
                btnPrimary.setOnClickListener(v -> {
                    RemixCollabFlow.openRecorder(this, target, mode);
                    finish();
                });
                btnSecondary.setText("Not now");
                btnSecondary.setOnClickListener(v -> finish());
                break;

            case RemixRequestHelper.REJECTED:
                icon(R.drawable.rc_ic_x, RED);
                tvTitle.setText("Request declined");
                tvMessage.setText(Character.toUpperCase(who().charAt(0)) + who().substring(1)
                        + " didn't allow a " + what().toLowerCase(Locale.ROOT) + " on this post.");
                btnPrimary.setText("Close");
                btnPrimary.setOnClickListener(v -> finish());
                btnSecondary.setVisibility(View.GONE);
                break;

            case RemixRequestHelper.EXPIRED:
                icon(R.drawable.rc_ic_clock, GREY);
                tvTitle.setText("Request expired");
                tvMessage.setText("No response in 48 hours. You can send a new request.");
                btnPrimary.setText("Send again");
                btnPrimary.setOnClickListener(v -> {
                    Intent i = new Intent(this, RemixRequestActivity.class);
                    target.put(i);
                    i.putExtra(RemixCollabTarget.X_MODE, mode);
                    startActivity(i);
                    finish();
                });
                btnSecondary.setText("Close");
                btnSecondary.setOnClickListener(v -> finish());
                break;

            case RemixRequestHelper.CANCELLED:
                icon(R.drawable.rc_ic_x, GREY);
                tvTitle.setText("Request cancelled");
                tvMessage.setText("You withdrew this request.");
                btnPrimary.setText("Close");
                btnPrimary.setOnClickListener(v -> finish());
                btnSecondary.setVisibility(View.GONE);
                break;

            default: // pending
                icon(R.drawable.rc_ic_plane, PURPLE);
                tvTitle.setText("Request Sent!");
                tvMessage.setText("Waiting for " + who() + "'s permission to create a " + what() + ".");
                cardExpiry.setVisibility(View.VISIBLE);
                startCountdown(expiresAt);
                btnPrimary.setText("Continue browsing");
                btnPrimary.setOnClickListener(v -> finish());
                btnSecondary.setText("Cancel request");
                btnSecondary.setOnClickListener(v -> RemixRequestHelper.cancel(target.ownerUid, requestId, ok -> {
                    if (!ok && !isFinishing())
                        Toast.makeText(this, "Couldn't cancel. Try again", Toast.LENGTH_SHORT).show();
                }));
        }
    }

    private void icon(int res, int color) {
        ivState.setImageResource(res);
        ivState.setColorFilter(color);
    }

    private void startCountdown(long expiresAt) {
        long left = expiresAt - System.currentTimeMillis();
        if (expiresAt <= 0 || left <= 0) { tvExpiry.setText("—"); return; }
        timer = new CountDownTimer(left, 30_000L) {
            @Override public void onTick(long ms) { tvExpiry.setText(fmt(ms)); }
            @Override public void onFinish() { tvExpiry.setText("0m"); }
        };
        tvExpiry.setText(fmt(left));
        timer.start();
    }

    private static String fmt(long ms) {
        long mins = Math.max(0, ms / 60_000L);
        return String.format(Locale.US, "%dh %02dm", mins / 60, mins % 60);
    }
}
