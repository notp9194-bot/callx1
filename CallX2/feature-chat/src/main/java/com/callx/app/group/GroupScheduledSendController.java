package com.callx.app.group;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.callx.app.chat.databinding.ActivityGroupChatBinding;
import com.callx.app.conversation.workers.ChatScheduledMessageWorker;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ScheduledMessageEntity;
import com.callx.app.models.Message;
import com.callx.app.models.ScheduledMessage;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;

import androidx.annotation.NonNull;

/**
 * Schedules a group chat message to auto-send at a future time via WorkManager.
 * Group-specific equivalent of ChatScheduledSendController — uses its own Delegate
 * interface so GroupChatActivity does not need to implement ChatActivityDelegate.
 *
 * Flow:
 *  1. showSchedulePicker(text, onSent) — DatePickerDialog → TimePickerDialog chain,
 *     then schedule(text, sendAt).
 *  2. schedule(text, sendAt) — writes ScheduledMessage to
 *     scheduledMessages/{groupId}/{id} in Firebase + Room cache, enqueues
 *     ChatScheduledMessageWorker for the target time.
 *  3. ChatScheduledMessageWorker fires (even after app kill), publishes the message
 *     to groupMessages/{groupId}, and removes the scheduled entry.
 */
public class GroupScheduledSendController {

    // ── Delegate ──────────────────────────────────────────────────────────

    public interface Delegate {
        Activity                 getActivity();
        String                   getGroupId();
        String                   getCurrentUid();
        String                   getCurrentName();
        AppDatabase              getDb();
        Executor                 getIoExecutor();
        ActivityGroupChatBinding getBinding();
        void                     showToast(String msg);
        /** Called (on the main thread) immediately after a message is scheduled. */
        void                     onMessageScheduled();
        /** Returns the Message currently being replied to, or null. */
        Message                  getReplyingTo();
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final Delegate delegate;
    private ValueEventListener scheduledListener;
    private final List<ScheduledMessage> pending = new ArrayList<>();

    // ── Constructor ───────────────────────────────────────────────────────

    public GroupScheduledSendController(Delegate delegate) {
        this.delegate = delegate;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Start listening for this group's scheduled messages (banner sync). */
    public void init() {
        watchScheduledMessages();
    }

    /** Remove Firebase listener. Call from onDestroy(). */
    public void release() {
        if (scheduledListener != null && delegate.getGroupId() != null) {
            FirebaseUtils.getScheduledMessagesRef(delegate.getGroupId())
                    .removeEventListener(scheduledListener);
            scheduledListener = null;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Show a date+time picker then schedule {@code text} to send at the chosen time.
     *
     * @param text   The message text to schedule (must not be empty).
     * @param onSent Optional Runnable called on the UI thread after scheduling (clears input).
     */
    public void showSchedulePicker(String text, Runnable onSent) {
        if (text == null || text.trim().isEmpty()) {
            delegate.showToast("Type a message before scheduling");
            return;
        }
        Activity activity = delegate.getActivity();
        if (activity == null || activity.isFinishing()) return;

        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(activity, (view, year, month, day) -> {
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, day);
            new TimePickerDialog(activity, (tv, hour, minute) -> {
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) {
                    delegate.showToast("Pick a time at least 1 minute in the future");
                    return;
                }
                schedule(text.trim(), cal.getTimeInMillis());
                delegate.onMessageScheduled();
                if (onSent != null) activity.runOnUiThread(onSent);
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ── Banner sync ───────────────────────────────────────────────────────

    private void watchScheduledMessages() {
        String groupId = delegate.getGroupId();
        if (groupId == null || groupId.isEmpty()) return;

        scheduledListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                pending.clear();
                for (DataSnapshot child : s.getChildren()) {
                    ScheduledMessage sm = child.getValue(ScheduledMessage.class);
                    if (sm == null) continue;
                    sm.id = child.getKey();
                    if (sm.senderId != null && sm.senderId.equals(delegate.getCurrentUid())) {
                        pending.add(sm);
                    }
                }
                pending.sort((a, b) -> Long.compare(a.sendAt, b.sendAt));
                updateBanner();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getScheduledMessagesRef(groupId).addValueEventListener(scheduledListener);
    }

    private void updateBanner() {
        ActivityGroupChatBinding binding = delegate.getBinding();
        if (binding == null) return;

        // The group layout may not have a scheduled-messages banner — skip gracefully.
        View bannerView = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_scheduled_banner);
        if (bannerView == null) return;

        if (pending.isEmpty()) {
            bannerView.setVisibility(View.GONE);
            bannerView.setOnClickListener(null);
            return;
        }

        ScheduledMessage soonest = pending.get(0);
        String when = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                .format(new Date(soonest.sendAt));
        String preview = pending.size() == 1
                ? when + "  ·  " + truncate(soonest.text)
                : pending.size() + " scheduled  ·  next " + when;

        TextView tvPreview = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.tv_scheduled_preview);
        if (tvPreview != null) tvPreview.setText(preview);

        bannerView.setVisibility(View.VISIBLE);
        bannerView.setOnClickListener(v -> showManageDialog());
    }

    // ── Private: write + enqueue ──────────────────────────────────────────

    private void schedule(String text, long sendAt) {
        String groupId = delegate.getGroupId();
        if (groupId == null) return;

        String id = UUID.randomUUID().toString();

        ScheduledMessage sm = new ScheduledMessage();
        sm.id         = id;
        sm.chatId     = groupId;
        sm.senderId   = delegate.getCurrentUid();
        sm.senderName = delegate.getCurrentName();
        sm.text       = text;
        sm.type       = "text";
        sm.sendAt     = sendAt;

        // Preserve reply-to linkage so the scheduled message, when it fires,
        // still appears as a reply to the original message.
        Message replyingTo = delegate.getReplyingTo();
        if (replyingTo != null) {
            sm.replyToId   = replyingTo.id;
            sm.replyToText = replyingTo.text;
        }

        // Firebase: the Worker picks this up when it fires.
        FirebaseUtils.getScheduledMessagesRef(groupId).child(id).setValue(sm);

        // Room: local cache so the banner works offline.
        ScheduledMessageEntity entity = new ScheduledMessageEntity();
        entity.id       = id;
        entity.chatId   = groupId;
        entity.senderId = sm.senderId;
        entity.text     = text;
        entity.type     = "text";
        entity.sendAt   = sendAt;
        delegate.getIoExecutor().execute(() ->
                delegate.getDb().scheduledMessageDao().insert(entity));

        // WorkManager: fires at sendAt even if the app is killed.
        ChatScheduledMessageWorker.schedule(delegate.getActivity(), id, groupId, sendAt);

        delegate.showToast("⏱ Scheduled for "
                + new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        .format(new Date(sendAt)));
    }

    // ── Manage dialog ─────────────────────────────────────────────────────

    private void showManageDialog() {
        Activity activity = delegate.getActivity();
        if (activity == null || activity.isFinishing() || pending.isEmpty()) return;

        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padPx = dpToPx(activity, 16);
        container.setPadding(padPx, padPx / 2, padPx, padPx / 2);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(container);

        for (ScheduledMessage sm : new ArrayList<>(pending)) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dpToPx(activity, 8), 0, dpToPx(activity, 8));

            TextView tv = new TextView(activity);
            tv.setText(fmt.format(new Date(sm.sendAt)) + "\n" + truncate(sm.text));
            tv.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            row.addView(tv);

            Button cancelBtn = new Button(activity);
            cancelBtn.setText("Cancel");
            cancelBtn.setOnClickListener(v -> {
                cancelScheduled(sm);
                container.removeView(row);
                if (container.getChildCount() == 0) {
                    // Dismiss the dialog if there's nothing left
                    View dialogRoot = scroll;
                    while (dialogRoot.getParent() instanceof View) {
                        dialogRoot = (View) dialogRoot.getParent();
                    }
                }
            });
            row.addView(cancelBtn);
            container.addView(row);
        }

        new AlertDialog.Builder(activity)
                .setTitle("Scheduled messages")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show();
    }

    private void cancelScheduled(ScheduledMessage sm) {
        if (sm.id == null) return;
        String groupId = delegate.getGroupId();
        if (groupId != null) {
            FirebaseUtils.getScheduledMessagesRef(groupId).child(sm.id).removeValue();
        }
        delegate.getIoExecutor().execute(() ->
                delegate.getDb().scheduledMessageDao().deleteById(sm.id));
        ChatScheduledMessageWorker.cancel(delegate.getActivity(), sm.id);
        pending.remove(sm);
        delegate.showToast("Scheduled message cancelled");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 60 ? text.substring(0, 57) + "…" : text;
    }

    private static int dpToPx(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density);
    }
}
