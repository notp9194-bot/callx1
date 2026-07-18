package com.callx.app.upload;

import com.callx.app.editor.ReelEditorActivity;

import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ReelSchedulerActivity — Schedule a reel to post at a specific future time.
 *
 * Features:
 *  ✅ Date + time picker for schedule time (must be at least 15 min in future)
 *  ✅ Thumbnail preview of reel to be scheduled
 *  ✅ Caption & audience summary
 *  ✅ Save schedule to Firebase: scheduledReels/{uid}/{scheduleId}
 *  ✅ System AlarmManager schedules broadcast to trigger upload at chosen time
 *  ✅ Scheduled queue list: shows all pending scheduled reels
 *  ✅ Cancel a scheduled reel (removes alarm + Firebase entry)
 *
 * Extras (input from ReelEditorActivity / ReelUploadActivity):
 *   EXTRA_VIDEO_URI, EXTRA_THUMB_URI, EXTRA_CAPTION, EXTRA_AUDIENCE, EXTRA_MUSIC_NAME
 */
public class ReelSchedulerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI  = "sched_video_uri";
    public static final String EXTRA_THUMB_URI  = "sched_thumb_uri";
    public static final String EXTRA_CAPTION    = "sched_caption";
    public static final String EXTRA_AUDIENCE   = "sched_audience";
    public static final String EXTRA_MUSIC_NAME = "sched_music";

    private ImageView ivThumbPreview;
    private TextView  tvCaptionPreview, tvAudiencePreview, tvMusicPreview;
    private TextView  tvSelectedDateTime;
    private View      btnPickDate, btnPickTime, btnSchedule;
    private ProgressBar progressSchedule;
    private RecyclerView rvScheduledQueue;
    private View      layoutNewSchedule, layoutQueue;

    private Calendar scheduledCalendar = Calendar.getInstance();
    private boolean  dateSelected = false, timeSelected = false;

    private String videoUri, thumbUri, caption, audience, musicName;
    private String myUid;

    private final List<Map<String, Object>> scheduledItems = new ArrayList<>();
    private ScheduledAdapter queueAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_scheduler);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        videoUri  = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        thumbUri  = getIntent().getStringExtra(EXTRA_THUMB_URI);
        caption   = getIntent().getStringExtra(EXTRA_CAPTION);
        audience  = getIntent().getStringExtra(EXTRA_AUDIENCE);
        musicName = getIntent().getStringExtra(EXTRA_MUSIC_NAME);

        bindViews();
        populatePreview();
        setupDateTimePickers();
        loadScheduledQueue();

        btnSchedule.setOnClickListener(v -> saveSchedule());

        ImageButton btnBack = findViewById(R.id.btn_scheduler_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
    }

    private void bindViews() {
        ivThumbPreview    = findViewById(R.id.iv_sched_thumb);
        tvCaptionPreview  = findViewById(R.id.tv_sched_caption);
        tvAudiencePreview = findViewById(R.id.tv_sched_audience);
        tvMusicPreview    = findViewById(R.id.tv_sched_music);
        tvSelectedDateTime= findViewById(R.id.tv_sched_datetime);
        btnPickDate       = findViewById(R.id.btn_sched_pick_date);
        btnPickTime       = findViewById(R.id.btn_sched_pick_time);
        btnSchedule       = findViewById(R.id.btn_sched_save);
        progressSchedule  = findViewById(R.id.progress_scheduler);
        rvScheduledQueue  = findViewById(R.id.rv_sched_queue);
        layoutNewSchedule = findViewById(R.id.layout_new_schedule);
        layoutQueue       = findViewById(R.id.layout_sched_queue);

        layoutNewSchedule.setVisibility(videoUri != null ? View.VISIBLE : View.GONE);

        queueAdapter = new ScheduledAdapter(scheduledItems, scheduleId -> cancelSchedule(scheduleId));
        rvScheduledQueue.setLayoutManager(new LinearLayoutManager(this));
        rvScheduledQueue.setAdapter(queueAdapter);
    }

    private void populatePreview() {
        if (thumbUri != null && !thumbUri.isEmpty()) {
            .override(480, 853)
            Glide.with(this).load(thumbUri).placeholder(R.drawable.ic_video).override(480, 853).into(ivThumbPreview);
        }
        tvCaptionPreview.setText(caption != null && !caption.isEmpty() ? caption : "No caption");
        tvAudiencePreview.setText("Audience: " + (audience != null ? audience : "everyone"));
        tvMusicPreview.setText("Music: " + (musicName != null && !musicName.isEmpty() ? musicName : "None"));
    }

    private void setupDateTimePickers() {
        scheduledCalendar = Calendar.getInstance();
        scheduledCalendar.add(Calendar.MINUTE, 30);
        updateDateTimeLabel();

        btnPickDate.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            new DatePickerDialog(this, (dp, year, month, day) -> {
                scheduledCalendar.set(Calendar.YEAR, year);
                scheduledCalendar.set(Calendar.MONTH, month);
                scheduledCalendar.set(Calendar.DAY_OF_MONTH, day);
                dateSelected = true;
                updateDateTimeLabel();
            }, scheduledCalendar.get(Calendar.YEAR),
               scheduledCalendar.get(Calendar.MONTH),
               scheduledCalendar.get(Calendar.DAY_OF_MONTH))
                .show();
        });

        btnPickTime.setOnClickListener(v -> {
            new TimePickerDialog(this, (tp, hour, minute) -> {
                scheduledCalendar.set(Calendar.HOUR_OF_DAY, hour);
                scheduledCalendar.set(Calendar.MINUTE, minute);
                scheduledCalendar.set(Calendar.SECOND, 0);
                timeSelected = true;
                updateDateTimeLabel();
            }, scheduledCalendar.get(Calendar.HOUR_OF_DAY),
               scheduledCalendar.get(Calendar.MINUTE), false)
                .show();
        });
    }

    private void updateDateTimeLabel() {
        String formatted = new SimpleDateFormat(
            "EEE, MMM d yyyy 'at' h:mm a", Locale.US).format(scheduledCalendar.getTime());
        tvSelectedDateTime.setText(formatted);
    }

    private void saveSchedule() {
        long schedTime = scheduledCalendar.getTimeInMillis();
        long minTime   = System.currentTimeMillis() + 15 * 60 * 1000L;
        if (schedTime < minTime) {
            Toast.makeText(this, "Schedule must be at least 15 minutes in the future",
                Toast.LENGTH_SHORT).show();
            return;
        }

        progressSchedule.setVisibility(View.VISIBLE);
        btnSchedule.setEnabled(false);

        String scheduleId = FirebaseUtils.db().getReference("scheduledReels")
            .child(myUid).push().getKey();
        if (scheduleId == null) scheduleId = UUID.randomUUID().toString();
        final String finalId = scheduleId;

        Map<String, Object> data = new HashMap<>();
        data.put("scheduleId",   finalId);
        data.put("uid",          myUid);
        data.put("videoUri",     videoUri != null ? videoUri : "");
        data.put("thumbUri",     thumbUri  != null ? thumbUri  : "");
        data.put("caption",      caption   != null ? caption   : "");
        data.put("audience",     audience  != null ? audience  : "everyone");
        data.put("musicName",    musicName != null ? musicName : "");
        data.put("scheduledAt",  schedTime);
        data.put("status",       "pending");
        data.put("createdAt",    System.currentTimeMillis());

        FirebaseUtils.db().getReference("scheduledReels").child(myUid).child(finalId)
            .setValue(data)
            .addOnSuccessListener(unused -> {
                scheduleAlarm(finalId, schedTime);
                progressSchedule.setVisibility(View.GONE);
                btnSchedule.setEnabled(true);
                Toast.makeText(this, "Reel scheduled!", Toast.LENGTH_SHORT).show();
                loadScheduledQueue();
                if (videoUri != null) layoutNewSchedule.setVisibility(View.GONE);
            })
            .addOnFailureListener(e -> {
                progressSchedule.setVisibility(View.GONE);
                btnSchedule.setEnabled(true);
                Toast.makeText(this, "Failed to schedule: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            });
    }

    private void scheduleAlarm(String scheduleId, long triggerAtMs) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(this, ScheduledReelReceiver.class);
        intent.putExtra("schedule_id", scheduleId);
        intent.putExtra("uid",         myUid);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(
            this, scheduleId.hashCode(), intent, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
        }
    }

    private void cancelSchedule(String scheduleId) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Cancel Schedule?")
            .setMessage("This scheduled reel will not be posted.")
            .setPositiveButton("Cancel Schedule", (d, w) -> {
                FirebaseUtils.db().getReference("scheduledReels")
                    .child(myUid).child(scheduleId).removeValue();

                AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (alarmManager != null) {
                    Intent intent = new Intent(this, ScheduledReelReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                    PendingIntent pi = PendingIntent.getBroadcast(
                        this, scheduleId.hashCode(), intent, flags);
                    alarmManager.cancel(pi);
                }
                loadScheduledQueue();
                Toast.makeText(this, "Schedule cancelled", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Keep", null)
            .show();
    }

    private void loadScheduledQueue() {
        FirebaseUtils.db().getReference("scheduledReels").child(myUid)
            .orderByChild("scheduledAt")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override @SuppressWarnings("unchecked")
                public void onDataChange(@NonNull DataSnapshot snap) {
                    scheduledItems.clear();
                    long now = System.currentTimeMillis();
                    for (DataSnapshot s : snap.getChildren()) {
                        Map<String, Object> item = (Map<String, Object>) s.getValue();
                        if (item == null) continue;
                        Object statusObj = item.get("status");
                        if ("pending".equals(statusObj)) {
                            Object schedAtObj = item.get("scheduledAt");
                            if (schedAtObj instanceof Long && (Long) schedAtObj > now) {
                                scheduledItems.add(item);
                            }
                        }
                    }
                    queueAdapter.notifyDataSetChanged();
                    layoutQueue.setVisibility(scheduledItems.isEmpty() ? View.GONE : View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Scheduled queue adapter ───────────────────────────────────────────

    interface OnCancelClick { void onCancel(String scheduleId); }

    static class ScheduledAdapter extends RecyclerView.Adapter<ScheduledAdapter.VH> {
        private final List<Map<String, Object>> items;
        private final OnCancelClick cancel;
        ScheduledAdapter(List<Map<String, Object>> i, OnCancelClick c) { items = i; cancel = c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scheduled_reel, parent, false);
            return new VH(v);
        }

        @Override @SuppressWarnings("unchecked")
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Map<String, Object> item = items.get(pos);
            String caption  = (String) item.get("caption");
            Object schedAt  = item.get("scheduledAt");
            String schedId  = (String) item.get("scheduleId");

            h.tvCaption.setText(caption != null && !caption.isEmpty() ? caption : "No caption");
            if (schedAt instanceof Long) {
                String formatted = new SimpleDateFormat(
                    "MMM d 'at' h:mm a", Locale.US).format(new Date((Long) schedAt));
                h.tvScheduledAt.setText("Scheduled: " + formatted);
            }
            h.btnCancel.setOnClickListener(v -> {
                if (schedId != null) cancel.onCancel(schedId);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView  tvCaption, tvScheduledAt;
            ImageButton btnCancel;
            VH(View v) {
                super(v);
                tvCaption     = v.findViewById(R.id.tv_sched_item_caption);
                tvScheduledAt = v.findViewById(R.id.tv_sched_item_time);
                btnCancel     = v.findViewById(R.id.btn_sched_item_cancel);
            }
        }
    }

    // ── Broadcast receiver (triggered by AlarmManager) ────────────────────

    public static class ScheduledReelReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String scheduleId = intent.getStringExtra("schedule_id");
            String uid        = intent.getStringExtra("uid");
            if (scheduleId == null || uid == null) return;

            // Mark as triggered in Firebase — actual upload handled server-side
            // or via a foreground service launched here
            FirebaseUtils.db().getReference("scheduledReels")
                .child(uid).child(scheduleId).child("status")
                .setValue("triggered");

            // Launch upload service / notification
            Intent notifyIntent = new Intent(context, ReelUploadActivity.class);
            notifyIntent.putExtra("from_schedule", true);
            notifyIntent.putExtra("schedule_id",   scheduleId);
            notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(notifyIntent);
        }
    }
}
