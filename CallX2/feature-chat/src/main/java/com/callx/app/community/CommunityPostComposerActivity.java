package com.callx.app.community;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * v31: Post composer — supports @mention autocomplete + scheduled post.
 * @mention: as the user types "@" a popup RecyclerView shows matching members.
 * Schedule: a "Schedule" button opens a datetime picker; saves to scheduledPosts.
 */
public class CommunityPostComposerActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID   = "communityId";
    public static final String EXTRA_IS_ANNOUNCEMENT = "isAnnouncement";
    public static final String EXTRA_CAN_ANNOUNCE    = "canAnnounce";

    private String communityId;
    private boolean defaultAnnouncement;
    private boolean canAnnounce;
    private String currentUid, currentName, currentPhoto;

    private EditText etPostText;
    private CheckBox cbAnnouncement;
    private ImageView ivMediaPreview;
    private MaterialButton btnPost, btnSchedule;
    private View btnAddMedia, btnAddPoll;
    private View layoutMentionSuggestions;
    private RecyclerView rvMentionSuggestions;
    private LinearLayout layoutSelectedMedia;

    private Uri pickedMediaUri;
    private String uploadedMediaUrl;
    private String mediaType = "image";

    private CommunityRepository repo;
    private List<CommunityMemberEntity> allMembers = new ArrayList<>();
    private List<String> pendingMentionedUids = new ArrayList<>();
    private CommunityMentionSuggestionsAdapter mentionAdapter;

    private ActivityResultLauncher<String> mediaPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_post_composer);

        communityId          = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        defaultAnnouncement  = getIntent().getBooleanExtra(EXTRA_IS_ANNOUNCEMENT, false);
        canAnnounce          = getIntent().getBooleanExtra(EXTRA_CAN_ANNOUNCE, false);
        repo = CommunityRepository.getInstance(this);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            currentUid   = user.getUid();
            currentName  = user.getDisplayName();
            currentPhoto = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : null;
        }

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        etPostText           = findViewById(R.id.et_post_text);
        cbAnnouncement       = findViewById(R.id.cb_announcement);
        ivMediaPreview       = findViewById(R.id.iv_media_preview);
        btnPost              = findViewById(R.id.btn_post);
        btnSchedule          = findViewById(R.id.btn_schedule);
        btnAddMedia          = findViewById(R.id.btn_add_media);
        btnAddPoll           = findViewById(R.id.btn_add_poll);
        layoutMentionSuggestions = findViewById(R.id.layout_mention_suggestions);
        rvMentionSuggestions = findViewById(R.id.rv_mention_suggestions);
        layoutSelectedMedia  = findViewById(R.id.layout_selected_media);

        cbAnnouncement.setVisibility(canAnnounce ? View.VISIBLE : View.GONE);
        cbAnnouncement.setChecked(defaultAnnouncement);

        mediaPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedMediaUri = uri;
            mediaType = uri.toString().contains("video") ? "video" : "image";
            ivMediaPreview.setVisibility(View.VISIBLE);
            Glide.with(this).load(uri).centerCrop().into(ivMediaPreview);
        });

        btnAddMedia.setOnClickListener(v -> mediaPicker.launch("image/*,video/*"));
        btnPost.setOnClickListener(v -> submitPost(false, 0L));
        if (btnSchedule != null) btnSchedule.setOnClickListener(v -> openScheduleDialog());
        if (btnAddPoll != null) btnAddPoll.setOnClickListener(v -> openPollBuilder());

        setupMentionAutocomplete();

        if (communityId != null) {
            repo.observeMembers(communityId).observe(this, members -> {
                allMembers = members != null ? members : new ArrayList<>();
            });
        }
    }

    private void setupMentionAutocomplete() {
        if (rvMentionSuggestions == null) return;
        mentionAdapter = new CommunityMentionSuggestionsAdapter(member -> {
            // Insert the member's name into the text
            String text = etPostText.getText().toString();
            int cursor = etPostText.getSelectionStart();
            int atPos = text.lastIndexOf('@', cursor - 1);
            if (atPos >= 0) {
                String before = text.substring(0, atPos);
                String after  = text.substring(cursor);
                String insert = "@" + member.name + " ";
                etPostText.setText(before + insert + after);
                etPostText.setSelection(before.length() + insert.length());
            }
            if (!pendingMentionedUids.contains(member.uid)) pendingMentionedUids.add(member.uid);
            layoutMentionSuggestions.setVisibility(View.GONE);
        });

        rvMentionSuggestions.setLayoutManager(new LinearLayoutManager(this));
        rvMentionSuggestions.setAdapter(mentionAdapter);

        etPostText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString();
                int cursor = etPostText.getSelectionStart();
                if (cursor <= 0) { hideMentions(); return; }
                int atPos = text.lastIndexOf('@', cursor - 1);
                if (atPos < 0) { hideMentions(); return; }
                String partial = text.substring(atPos + 1, cursor).toLowerCase(Locale.getDefault());
                if (partial.contains(" ")) { hideMentions(); return; }
                filterMentions(partial);
            }
        });
    }

    private void filterMentions(String query) {
        if (allMembers.isEmpty()) { hideMentions(); return; }
        List<CommunityMemberEntity> filtered = new ArrayList<>();
        for (CommunityMemberEntity m : allMembers) {
            if (m.name != null && m.name.toLowerCase(Locale.getDefault()).contains(query)
                    && !m.uid.equals(currentUid)) {
                filtered.add(m);
                if (filtered.size() >= 5) break;
            }
        }
        if (filtered.isEmpty()) { hideMentions(); return; }
        mentionAdapter.submitList(filtered);
        layoutMentionSuggestions.setVisibility(View.VISIBLE);
    }

    private void hideMentions() {
        if (layoutMentionSuggestions != null)
            layoutMentionSuggestions.setVisibility(View.GONE);
    }

    private void openScheduleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_community_schedule_post, null);
        TextView tvSelectedDatetime = dialogView.findViewById(R.id.tv_selected_datetime);
        View btnPickDate = dialogView.findViewById(R.id.btn_pick_date);
        View btnPickTime = dialogView.findViewById(R.id.btn_pick_time);
        View btnScheduleConfirm = dialogView.findViewById(R.id.btn_schedule_confirm);

        Calendar cal = Calendar.getInstance();
        boolean[] datePicked = {false};
        boolean[] timePicked = {false};

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();

        btnPickDate.setOnClickListener(v -> {
            new DatePickerDialog(this, (dp, y, m, d) -> {
                cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d);
                datePicked[0] = true;
                updateScheduleLabel(tvSelectedDatetime, cal, datePicked[0], timePicked[0]);
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
        btnPickTime.setOnClickListener(v -> {
            new TimePickerDialog(this, (tp, h, min) -> {
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min);
                timePicked[0] = true;
                updateScheduleLabel(tvSelectedDatetime, cal, datePicked[0], timePicked[0]);
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show();
        });
        btnScheduleConfirm.setOnClickListener(v -> {
            if (!datePicked[0]) {
                Toast.makeText(this, "Please pick a date", Toast.LENGTH_SHORT).show();
                return;
            }
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                Toast.makeText(this, "Scheduled time must be in the future", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            submitPost(true, cal.getTimeInMillis());
        });

        dialog.show();
    }

    private void updateScheduleLabel(TextView tv, Calendar cal, boolean datePicked, boolean timePicked) {
        if (!datePicked && !timePicked) return;
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d yyyy 'at' h:mm a", Locale.getDefault());
        tv.setVisibility(View.VISIBLE);
        tv.setText(sdf.format(cal.getTime()));
    }

    private void openPollBuilder() {
        // Reuse existing poll builder UI — launch CommunityPollBuilderActivity if it exists,
        // otherwise show a simplified inline dialog
        Toast.makeText(this, "Poll builder — attach via existing flow", Toast.LENGTH_SHORT).show();
    }

    private void submitPost(boolean isScheduled, long scheduledAt) {
        String text = etPostText.getText().toString().trim();
        if (text.isEmpty() && pickedMediaUri == null) {
            Toast.makeText(this, "Write something or add media", Toast.LENGTH_SHORT).show();
            return;
        }
        btnPost.setEnabled(false);
        if (btnSchedule != null) btnSchedule.setEnabled(false);

        boolean isAnnouncement = canAnnounce && cbAnnouncement != null && cbAnnouncement.isChecked();

        if (pickedMediaUri != null) {
            CloudinaryUploader.upload(this, pickedMediaUri, "callx/community_posts", mediaType,
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result result) {
                            runOnUiThread(() -> doSubmit(text, result.secureUrl, mediaType,
                                    isAnnouncement, isScheduled, scheduledAt));
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> {
                                btnPost.setEnabled(true);
                                if (btnSchedule != null) btnSchedule.setEnabled(true);
                                Toast.makeText(CommunityPostComposerActivity.this,
                                        "Media upload failed: " + message, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        } else {
            doSubmit(text, null, null, isAnnouncement, isScheduled, scheduledAt);
        }
    }

    private void doSubmit(String text, String mediaUrl, String mType,
                          boolean isAnnouncement, boolean isScheduled, long scheduledAt) {
        if (isScheduled) {
            repo.schedulePost(communityId, currentUid, currentName, currentPhoto,
                    text, mediaUrl, mType, isAnnouncement, null, scheduledAt,
                    (success, error) -> runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(this, "Post scheduled!", Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            btnPost.setEnabled(true);
                            if (btnSchedule != null) btnSchedule.setEnabled(true);
                            Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                        }
                    }));
        } else {
            repo.createPostWithMentions(communityId, currentUid, currentName, currentPhoto,
                    text, mediaUrl, mType, isAnnouncement, null, pendingMentionedUids,
                    (success, error) -> runOnUiThread(() -> {
                        if (success) {
                            finish();
                        } else {
                            btnPost.setEnabled(true);
                            if (btnSchedule != null) btnSchedule.setEnabled(true);
                            Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                        }
                    }));
        }
    }
}
