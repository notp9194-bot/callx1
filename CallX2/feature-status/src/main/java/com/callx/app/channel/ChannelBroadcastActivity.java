package com.callx.app.channel;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * ChannelBroadcastActivity — broadcast an announcement to ALL followers (v5).
 *
 * v5 additions/fixes:
 *   ✓ FIXED: "Notify all followers" toggle now actually triggers FCM push
 *     via ChannelViewModel.createBroadcastPost → ChannelRepository.sendBroadcastPush
 *   ✓ NEW: Schedule broadcast (date+time picker) — creates scheduled broadcast post
 *   ✓ NEW: Character counter fixed to 1000 chars (broadcast-appropriate limit)
 *   ✓ NEW: Preview card updates live with priority badge color
 *   ✓ Priority selector: Normal / Important / Urgent (badge color + FCM priority)
 *   ✓ Confirmation dialog before sending with summary of recipients
 *   ✓ Only admins/owners can access this screen
 */
public class ChannelBroadcastActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final int MAX_CHARS = 1000;

    private ChannelViewModel viewModel;
    private String channelId, channelName;

    private TextInputEditText etBroadcastText;
    private TextView          tvCharCount;
    private MaterialButton    btnSend, btnSchedule;
    private ProgressBar       progressBar;
    private RadioGroup        rgPriority;
    private Switch            switchNotifyAll;
    private LinearLayout      layoutPreview;
    private TextView          tvPreviewText, tvPreviewBadge;
    private View              layoutScheduleInfo;
    private TextView          tvScheduleLabel;

    private Calendar scheduledTime = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_broadcast);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_broadcast);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Broadcast to followers");
            getSupportActionBar().setSubtitle(channelName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etBroadcastText = findViewById(R.id.et_broadcast_text);
        tvCharCount     = findViewById(R.id.tv_broadcast_char_count);
        btnSend         = findViewById(R.id.btn_broadcast_send);
        btnSchedule     = findViewById(R.id.btn_broadcast_schedule);
        progressBar     = findViewById(R.id.progress_broadcast);
        rgPriority      = findViewById(R.id.rg_broadcast_priority);
        switchNotifyAll = findViewById(R.id.switch_notify_all);
        layoutPreview   = findViewById(R.id.layout_broadcast_preview);
        tvPreviewText   = findViewById(R.id.tv_broadcast_preview_text);
        tvPreviewBadge  = findViewById(R.id.tv_broadcast_preview_badge);
        layoutScheduleInfo = findViewById(R.id.layout_broadcast_schedule_info);
        tvScheduleLabel    = findViewById(R.id.tv_broadcast_schedule_label);

        // Char counter
        if (etBroadcastText != null) {
            etBroadcastText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    int len = s.length();
                    if (tvCharCount != null) {
                        tvCharCount.setText(len + " / " + MAX_CHARS);
                        tvCharCount.setTextColor(len > MAX_CHARS ? 0xFFFF3B30 : 0xFF888888);
                    }
                    updatePreview();
                    if (btnSend != null) btnSend.setEnabled(len > 0 && len <= MAX_CHARS);
                }
            });
        }

        // Priority → update badge color in preview
        if (rgPriority != null) {
            rgPriority.setOnCheckedChangeListener((group, id) -> updatePreview());
        }

        // Schedule button
        if (btnSchedule != null) {
            btnSchedule.setOnClickListener(v -> pickScheduleDateTime());
        }

        // Send button — shows confirmation dialog
        if (btnSend != null) {
            btnSend.setEnabled(false);
            btnSend.setOnClickListener(v -> confirmSend());
        }

        // Observe success
        viewModel.postSuccess.observe(this, ok -> {
            if (ok != null && ok) {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                String msg = scheduledTime != null
                    ? "Broadcast scheduled for " + formatCalendar(scheduledTime)
                    : "Broadcast sent!";
                Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show();
                finish();
            }
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty())
                Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
        });

        // Default: notify all ON
        if (switchNotifyAll != null) switchNotifyAll.setChecked(true);
    }

    // ── Preview update ────────────────────────────────────────────────────

    private void updatePreview() {
        String text = etBroadcastText != null && etBroadcastText.getText() != null
            ? etBroadcastText.getText().toString().trim() : "";
        if (tvPreviewText != null) tvPreviewText.setText(text.isEmpty() ? "Broadcast preview..." : text);

        String priority = getSelectedPriority();
        if (tvPreviewBadge != null) {
            switch (priority) {
                case "urgent":
                    tvPreviewBadge.setText("🚨 URGENT");
                    tvPreviewBadge.setBackgroundColor(0xFFFF3B30);
                    break;
                case "important":
                    tvPreviewBadge.setText("⚠️ IMPORTANT");
                    tvPreviewBadge.setBackgroundColor(0xFFFF9500);
                    break;
                default:
                    tvPreviewBadge.setText("📢 Broadcast");
                    tvPreviewBadge.setBackgroundColor(0xFF25D366);
            }
        }
        if (layoutPreview != null) layoutPreview.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Schedule picker ───────────────────────────────────────────────────

    private void pickScheduleDateTime() {
        Calendar now = Calendar.getInstance();
        new android.app.DatePickerDialog(this, (dp, y, m, d) ->
            new android.app.TimePickerDialog(this, (tp, h, min) -> {
                scheduledTime = Calendar.getInstance();
                scheduledTime.set(y, m, d, h, min, 0);
                if (scheduledTime.before(Calendar.getInstance())) {
                    Toast.makeText(this, "Choose a future time.", Toast.LENGTH_SHORT).show();
                    scheduledTime = null; return;
                }
                if (tvScheduleLabel    != null) tvScheduleLabel.setText("📅 " + formatCalendar(scheduledTime));
                if (layoutScheduleInfo != null) layoutScheduleInfo.setVisibility(View.VISIBLE);
                if (btnSend != null) btnSend.setText("Schedule");
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show(),
        now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Confirm send ──────────────────────────────────────────────────────

    private void confirmSend() {
        String text = etBroadcastText != null && etBroadcastText.getText() != null
            ? etBroadcastText.getText().toString().trim() : "";
        if (text.isEmpty()) return;

        boolean notifyAll = switchNotifyAll != null && switchNotifyAll.isChecked();
        String priority   = getSelectedPriority();

        String actionLabel = scheduledTime != null
            ? "Schedule broadcast for " + formatCalendar(scheduledTime)
            : "Send broadcast now";
        String msg = actionLabel + "?\n\nPriority: " + priority.toUpperCase(Locale.US)
            + (notifyAll ? "\nAll followers will receive a push notification." : "");

        new AlertDialog.Builder(this)
            .setTitle("Confirm broadcast")
            .setMessage(msg)
            .setPositiveButton(scheduledTime != null ? "Schedule" : "Send", (d, w) -> {
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (btnSend     != null) btnSend.setEnabled(false);
                long scheduledMs = scheduledTime != null ? scheduledTime.getTimeInMillis() : 0;
                // ── FIXED: actually passes notifyAll to ViewModel which sends FCM ──
                viewModel.createBroadcastPost(channelId, text, priority, notifyAll, scheduledMs);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String getSelectedPriority() {
        if (rgPriority == null) return "normal";
        int id = rgPriority.getCheckedRadioButtonId();
        if (id == R.id.rb_priority_urgent)    return "urgent";
        if (id == R.id.rb_priority_important) return "important";
        return "normal";
    }

    private String formatCalendar(Calendar cal) {
        return new SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(cal.getTime());
    }
}
