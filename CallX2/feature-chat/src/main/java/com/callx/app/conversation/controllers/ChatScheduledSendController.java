package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.workers.ChatScheduledMessageWorker;
import com.callx.app.db.entity.ScheduledMessageEntity;
import com.callx.app.models.ScheduledMessage;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Handles scheduling a chat message to auto-send at a future time via
 * WorkManager — text-only, modeled directly on the X module's
 * XScheduledPostWorker / showSchedulePicker flow (see XComposeActivity).
 *
 * Flow:
 *   1. showSchedulePicker()  — date+time picker (same DatePickerDialog →
 *      TimePickerDialog chain as XComposeActivity), then schedule(text, at).
 *   2. schedule(text, at)    — writes a ScheduledMessage to
 *      scheduledMessages/{chatId}/{id} in Firebase + a local Room cache
 *      row, then enqueues ChatScheduledMessageWorker for that time.
 *   3. ChatScheduledMessageWorker fires later (even if the app is killed),
 *      publishes the message into the live messages/{chatId} node, and
 *      removes the scheduled entry.
 *
 * The "⏱ Scheduled" banner (ll_scheduled_banner in activity_chat.xml) is
 * kept in sync here via a Firebase listener — purely additive, never
 * touches the regular message list/paging source.
 */
public class ChatScheduledSendController {

    private final ChatActivityDelegate delegate;
    private ValueEventListener scheduledListener;
    /** Most recent snapshot of pending scheduled messages for this chat,
     *  newest-sendAt-first isn't required here — kept sorted by sendAt ASC
     *  (soonest first) since that's what the banner and manage list want. */
    private final List<ScheduledMessage> pending = new java.util.ArrayList<>();

    public ChatScheduledSendController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init / cleanup ────────────────────────────────────────────────────

    public void init() {
        watchScheduledMessages();
    }

    public void release() {
        if (scheduledListener != null && delegate.getChatId() != null) {
            FirebaseUtils.getScheduledMessagesRef(delegate.getChatId())
                    .removeEventListener(scheduledListener);
        }
    }

    // ── Banner ────────────────────────────────────────────────────────────

    private void watchScheduledMessages() {
        String chatId = delegate.getChatId();
        if (chatId == null || chatId.isEmpty()) return;

        scheduledListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                pending.clear();
                for (DataSnapshot child : s.getChildren()) {
                    ScheduledMessage sm = child.getValue(ScheduledMessage.class);
                    if (sm == null) continue;
                    sm.id = child.getKey();
                    // Only show entries this device actually queued for —
                    // each participant only sees/cancels their own scheduled
                    // sends, never the partner's (mirrors how drafts work).
                    if (sm.senderId != null && sm.senderId.equals(delegate.getCurrentUid())) {
                        pending.add(sm);
                    }
                }
                pending.sort((a, b) -> Long.compare(a.sendAt, b.sendAt));
                updateBanner();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getScheduledMessagesRef(chatId).addValueEventListener(scheduledListener);
    }

    private void updateBanner() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llScheduledBanner == null) return;

        if (pending.isEmpty()) {
            binding.llScheduledBanner.setVisibility(View.GONE);
            binding.llScheduledBanner.setOnClickListener(null);
            return;
        }

        ScheduledMessage soonest = pending.get(0);
        String when = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                .format(new Date(soonest.sendAt));
        String preview = pending.size() == 1
                ? when + "  ·  " + truncate(soonest.text)
                : pending.size() + " messages queued  ·  next " + when;

        if (binding.tvScheduledPreview != null) binding.tvScheduledPreview.setText(preview);
        binding.llScheduledBanner.setVisibility(View.VISIBLE);
        binding.llScheduledBanner.setOnClickListener(v -> showManageDialog());
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    // ── Schedule picker (date + time) ────────────────────────────────────

    /** Opens the same DatePickerDialog → TimePickerDialog chain as
     *  XComposeActivity#showSchedulePicker, then queues the given text.
     *  onScheduled fires only after a successful schedule (lets the caller
     *  clear the input box etc. — mirrors how sendTextMessage clears it). */
    public void showSchedulePicker(String text, Runnable onScheduled) {
        if (text == null || text.trim().isEmpty()) {
            delegate.showToast("Type a message first, then long-press send to schedule it");
            return;
        }
        Context ctx = delegate.getActivity();
        if (ctx == null) return;

        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(ctx, (d, y, mo, day) -> {
            new TimePickerDialog(ctx, (t, h, min) -> {
                cal.set(y, mo, day, h, min, 0);
                long sendAt = cal.getTimeInMillis();
                if (sendAt <= System.currentTimeMillis()) {
                    delegate.showToast("Pick a time in the future");
                    return;
                }
                scheduleMessage(text, sendAt);
                if (onScheduled != null) onScheduled.run();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void scheduleMessage(String text, long sendAt) {
        String chatId = delegate.getChatId();
        if (chatId == null) return;

        String scheduleId = FirebaseUtils.getScheduledMessagesRef(chatId).push().getKey();
        if (scheduleId == null) return;

        ScheduledMessage sm = new ScheduledMessage();
        sm.id          = scheduleId;
        sm.chatId      = chatId;
        sm.senderId    = delegate.getCurrentUid();
        sm.senderName  = delegate.getCurrentName();
        sm.partnerUid  = delegate.getPartnerUid();
        sm.text        = text;
        sm.type        = "text";
        sm.sendAt      = sendAt;
        sm.createdAt   = System.currentTimeMillis();

        // Firebase — single write, the watchScheduledMessages() listener
        // above picks this up and refreshes the banner automatically.
        FirebaseUtils.getScheduledMessagesRef(chatId).child(scheduleId).setValue(sm);

        // Room — local cache so the manage-list/banner survive offline.
        delegate.getIoExecutor().execute(() -> {
            ScheduledMessageEntity e = new ScheduledMessageEntity();
            e.id         = scheduleId;
            e.chatId     = chatId;
            e.senderId   = sm.senderId;
            e.senderName = sm.senderName;
            e.partnerUid = sm.partnerUid;
            e.text       = sm.text;
            e.type       = sm.type;
            e.sendAt     = sm.sendAt;
            e.createdAt  = sm.createdAt;
            delegate.getDb().scheduledMessageDao().insert(e);
        });

        // WorkManager — fires sendAt even if the app is killed in the meantime.
        ChatScheduledMessageWorker.schedule(delegate.getActivity(), chatId, scheduleId, sendAt);

        String when = new SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
                .format(new Date(sendAt));
        delegate.showToast("Message scheduled for " + when);
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    private void cancelScheduled(ScheduledMessage sm) {
        String chatId = delegate.getChatId();
        if (chatId == null || sm.id == null) return;
        ChatScheduledMessageWorker.cancel(delegate.getActivity(), chatId, sm.id);
        delegate.getIoExecutor().execute(() ->
                delegate.getDb().scheduledMessageDao().deleteById(sm.id));
        delegate.showToast("Scheduled message cancelled");
    }

    // ── Manage dialog (tap the banner) ───────────────────────────────────

    private void showManageDialog() {
        Context ctx = delegate.getActivity();
        if (ctx == null || pending.isEmpty()) return;

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        container.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));
        scroll.addView(container);

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

        // Snapshot the list so a cancel mid-dialog can't shift indices under us.
        List<ScheduledMessage> rows = new java.util.ArrayList<>(pending);
        for (ScheduledMessage sm : rows) {
            container.addView(buildManageRow(ctx, sm, fmt));
        }

        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(ctx)
                .setTitle("Scheduled messages")
                .setView(scroll)
                .setPositiveButton("Close", null)
        .create());
    }

    private View buildManageRow(Context ctx, ScheduledMessage sm, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(colLp);

        TextView tvWhen = new TextView(ctx);
        tvWhen.setText(fmt.format(new Date(sm.sendAt)));
        tvWhen.setTextSize(11);
        tvWhen.setTypeface(tvWhen.getTypeface(), android.graphics.Typeface.BOLD);
        tvWhen.setTextColor(0xFF1565C0);
        textCol.addView(tvWhen);

        TextView tvText = new TextView(ctx);
        tvText.setText(sm.text != null ? sm.text : "");
        tvText.setTextSize(15);
        tvText.setTextColor(0xFF222222);
        tvText.setMaxLines(3);
        tvText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(tvText);

        row.addView(textCol);

        ImageButton btnCancel = new ImageButton(ctx);
        btnCancel.setImageResource(com.callx.app.chat.R.drawable.ic_close);
        btnCancel.setBackground(null);
        btnCancel.setContentDescription("Cancel");
        int btnSize = dp(ctx, 36);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
        btnCancel.setOnClickListener(v -> {
            cancelScheduled(sm);
            row.setVisibility(View.GONE);
        });
        row.addView(btnCancel);

        return row;
    }

    private int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
