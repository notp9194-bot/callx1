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
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.conversation.controllers.MediaEditActivity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.ImageCompressor;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * v34: Post composer — upgraded with multi-media carousel (up to 5 media items).
 *
 * Changes from v31:
 *  - Multiple media can be added (up to 5): each shows a thumbnail strip
 *  - "Add Media" becomes a strip of thumbnail cards + an "+" add button
 *  - Primary mediaUrl = first item; mediaUrlsJson = full array
 *  - mediaTypesJson = parallel array of "image"|"video"
 *  - @mention autocomplete preserved from v31
 *  - Scheduled post preserved from v31
 */
public class CommunityPostComposerActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID    = "communityId";
    public static final String EXTRA_IS_ANNOUNCEMENT = "isAnnouncement";
    public static final String EXTRA_CAN_ANNOUNCE    = "canAnnounce";

    private static final int MAX_MEDIA = 5;

    private String communityId;
    private boolean defaultAnnouncement, canAnnounce;
    private String currentUid, currentName, currentPhoto;

    private EditText etPostText;
    private CheckBox cbAnnouncement;
    private MaterialButton btnPost, btnSchedule;
    private View btnAddMedia, btnAddPoll;
    private View layoutMentionSuggestions;
    private RecyclerView rvMentionSuggestions;

    // v34: multi-media carousel strip
    private HorizontalScrollView scrollMediaStrip;
    private LinearLayout layoutMediaStrip;
    private View btnAddMoreMedia;

    private final List<Uri>    pickedUris   = new ArrayList<>();
    private final List<String> uploadedUrls = new ArrayList<>();
    private final List<String> mediaTypes   = new ArrayList<>();

    private CommunityRepository repo;
    private List<CommunityMemberEntity> allMembers = new ArrayList<>();
    private List<String> pendingMentionedUids = new ArrayList<>();
    private CommunityMentionSuggestionsAdapter mentionAdapter;

    // For scheduled posts
    private Calendar scheduledCal = null;
    private TextView tvScheduledLabel;

    private ActivityResultLauncher<String> mediaPicker;
    private ActivityResultLauncher<Intent> mediaEditLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_post_composer);

        communityId         = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        defaultAnnouncement = getIntent().getBooleanExtra(EXTRA_IS_ANNOUNCEMENT, false);
        canAnnounce         = getIntent().getBooleanExtra(EXTRA_CAN_ANNOUNCE, false);
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

        etPostText            = findViewById(R.id.et_post_text);
        cbAnnouncement        = findViewById(R.id.cb_announcement);
        btnPost               = findViewById(R.id.btn_post);
        btnSchedule           = findViewById(R.id.btn_schedule);
        btnAddMedia           = findViewById(R.id.btn_add_media);
        btnAddPoll            = findViewById(R.id.btn_add_poll);
        layoutMentionSuggestions = findViewById(R.id.layout_mention_suggestions);
        rvMentionSuggestions  = findViewById(R.id.rv_mention_suggestions);
        scrollMediaStrip      = findViewById(R.id.scroll_media_strip);
        layoutMediaStrip      = findViewById(R.id.layout_media_strip);
        btnAddMoreMedia       = findViewById(R.id.btn_add_more_media);
        tvScheduledLabel      = findViewById(R.id.tv_scheduled_label);

        cbAnnouncement.setVisibility(canAnnounce ? View.VISIBLE : View.GONE);
        cbAnnouncement.setChecked(defaultAnnouncement);

        // Media pickers
        mediaPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            if (pickedUris.size() >= MAX_MEDIA) {
                Toast.makeText(this, "Max " + MAX_MEDIA + " media items", Toast.LENGTH_SHORT).show();
                return;
            }
            launchMediaEditor(uri);
        });
        mediaEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri edited = result.getData().getParcelableExtra(MediaEditActivity.RESULT_URIS);
                        String type = result.getData().getStringExtra(MediaEditActivity.EXTRA_MEDIA_TYPE_COMPAT);
                        if (edited != null) addMediaToStrip(edited, type != null ? type : "image");
                    }
                });

        btnAddMedia.setOnClickListener(v -> mediaPicker.launch("image/*,video/*"));
        if (btnAddMoreMedia != null)
            btnAddMoreMedia.setOnClickListener(v -> mediaPicker.launch("image/*,video/*"));

        btnPost.setOnClickListener(v -> publishOrSchedule(false));
        if (btnSchedule != null) btnSchedule.setOnClickListener(v -> pickScheduleTime());

        setupMentionAutocomplete();
        loadMentionMembers();
    }

    // ─── Media strip ─────────────────────────────────────────────────────────

    private void launchMediaEditor(Uri uri) {
        // Determine type
        String mime = getContentResolver().getType(uri);
        String type = (mime != null && mime.startsWith("video")) ? "video" : "image";

        if ("video".equals(type)) {
            addMediaToStrip(uri, "video");
            return;
        }
        // Launch image editor for images
        Intent editIntent = new Intent(this, MediaEditActivity.class);
        editIntent.putExtra(MediaEditActivity.EXTRA_URIS, uri.toString());
        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE_COMPAT, type);
        mediaEditLauncher.launch(editIntent);
    }

    private void addMediaToStrip(Uri uri, String type) {
        if (pickedUris.size() >= MAX_MEDIA) return;
        pickedUris.add(uri);
        mediaTypes.add(type);
        updateMediaStrip();
    }

    private void updateMediaStrip() {
        if (layoutMediaStrip == null) return;
        layoutMediaStrip.removeAllViews();
        boolean hasMedia = !pickedUris.isEmpty();
        if (scrollMediaStrip != null) scrollMediaStrip.setVisibility(hasMedia ? View.VISIBLE : View.GONE);

        int dpPx = (int)(96 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < pickedUris.size(); i++) {
            final int idx = i;
            View thumb = LayoutInflater.from(this).inflate(R.layout.item_media_strip_thumb, layoutMediaStrip, false);
            ImageView ivThumb = thumb.findViewById(R.id.iv_strip_thumb);
            View btnRemove    = thumb.findViewById(R.id.btn_strip_remove);
            if ("video".equals(mediaTypes.get(i))) {
                Glide.with(this).asBitmap().load(pickedUris.get(i)).centerCrop()
                        .override(dpPx, dpPx).into(ivThumb);
                // add video indicator overlay
                ImageView ivPlay = thumb.findViewById(R.id.iv_strip_video_icon);
                if (ivPlay != null) ivPlay.setVisibility(View.VISIBLE);
            } else {
                Glide.with(this).load(pickedUris.get(i)).centerCrop()
                        .override(dpPx, dpPx).into(ivThumb);
            }
            btnRemove.setOnClickListener(v -> {
                pickedUris.remove(idx);
                mediaTypes.remove(idx);
                updateMediaStrip();
            });
            layoutMediaStrip.addView(thumb);
        }

        // "+" Add more button
        if (pickedUris.size() < MAX_MEDIA && btnAddMoreMedia != null) {
            btnAddMoreMedia.setVisibility(View.VISIBLE);
        } else if (btnAddMoreMedia != null) {
            btnAddMoreMedia.setVisibility(View.GONE);
        }
    }

    // ─── @mention autocomplete ────────────────────────────────────────────────

    private void loadMentionMembers() {
        repo.observeMembers(communityId).observe(this, members -> {
            allMembers = members != null ? members : new ArrayList<>();
            if (mentionAdapter != null) mentionAdapter.setMembers(allMembers);
        });
    }

    private void setupMentionAutocomplete() {
        mentionAdapter = new CommunityMentionSuggestionsAdapter(uid -> {
            String text   = etPostText.getText().toString();
            int cursor    = etPostText.getSelectionStart();
            int atPos     = text.lastIndexOf('@', cursor - 1);
            if (atPos >= 0) {
                CommunityMemberEntity picked = null;
                for (CommunityMemberEntity m : allMembers) {
                    if (uid.equals(m.uid)) { picked = m; break; }
                }
                if (picked != null) {
                    if (!pendingMentionedUids.contains(uid)) pendingMentionedUids.add(uid);
                    String before  = text.substring(0, atPos);
                    String after   = text.substring(cursor);
                    String mention = "@" + picked.name + " ";
                    etPostText.setText(before + mention + after);
                    etPostText.setSelection((before + mention).length());
                }
            }
            layoutMentionSuggestions.setVisibility(View.GONE);
        });
        rvMentionSuggestions.setLayoutManager(new LinearLayoutManager(this));
        rvMentionSuggestions.setAdapter(mentionAdapter);

        etPostText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                int cursor = etPostText.getSelectionStart();
                String text = s.toString();
                int atPos   = text.lastIndexOf('@', cursor - 1);
                if (atPos >= 0) {
                    String prefix = text.substring(atPos + 1, Math.min(cursor, text.length()));
                    if (!prefix.contains(" ")) {
                        List<CommunityMemberEntity> filtered = new ArrayList<>();
                        for (CommunityMemberEntity m : allMembers) {
                            if (m.name != null && m.name.toLowerCase()
                                    .startsWith(prefix.toLowerCase())) filtered.add(m);
                            if (filtered.size() >= 5) break;
                        }
                        mentionAdapter.setMembers(filtered);
                        layoutMentionSuggestions.setVisibility(
                                filtered.isEmpty() ? View.GONE : View.VISIBLE);
                        return;
                    }
                }
                layoutMentionSuggestions.setVisibility(View.GONE);
            }
        });
    }

    // ─── Schedule ─────────────────────────────────────────────────────────────

    private void pickScheduleTime() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(this, (dp, y, m, d) -> {
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d);
            new TimePickerDialog(this, (tp, hr, min) -> {
                cal.set(Calendar.HOUR_OF_DAY, hr); cal.set(Calendar.MINUTE, min);
                scheduledCal = cal;
                SimpleDateFormat sdf = new SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault());
                if (tvScheduledLabel != null) {
                    tvScheduledLabel.setVisibility(View.VISIBLE);
                    tvScheduledLabel.setText("⏰ Scheduled: " + sdf.format(cal.getTime()));
                }
                Toast.makeText(this, "Post scheduled for " + sdf.format(cal.getTime()), Toast.LENGTH_SHORT).show();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ─── Publish ──────────────────────────────────────────────────────────────

    private void publishOrSchedule(boolean retry) {
        String text = etPostText.getText().toString().trim();
        if (text.isEmpty() && pickedUris.isEmpty()) {
            Toast.makeText(this, "Write something or add a photo/video", Toast.LENGTH_SHORT).show();
            return;
        }
        btnPost.setEnabled(false);
        Toast.makeText(this, pickedUris.isEmpty() ? "Posting…" : "Uploading media…",
                Toast.LENGTH_SHORT).show();

        if (pickedUris.isEmpty()) {
            doPost(text, new ArrayList<>(), new ArrayList<>());
            return;
        }

        // Upload all media in parallel
        uploadedUrls.clear();
        final int[] done = {0};
        final int total  = pickedUris.size();
        final String[] urls  = new String[total];
        final String[] types = mediaTypes.toArray(new String[0]);

        for (int i = 0; i < total; i++) {
            final int idx = i;
            new CloudinaryUploader().uploadFile(this, pickedUris.get(i), "callx/posts",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result r) {
                            urls[idx] = r.secureUrl;
                            done[0]++;
                            if (done[0] >= total) {
                                List<String> urlList  = new ArrayList<>();
                                List<String> typeList = new ArrayList<>();
                                for (int j = 0; j < total; j++) {
                                    if (urls[j] != null) { urlList.add(urls[j]); typeList.add(types[j]); }
                                }
                                runOnUiThread(() -> doPost(text, urlList, typeList));
                            }
                        }
                        @Override public void onError(String msg) {
                            runOnUiThread(() -> {
                                btnPost.setEnabled(true);
                                Toast.makeText(CommunityPostComposerActivity.this,
                                        "Upload failed: " + msg, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        }
    }

    private void doPost(String text, List<String> urlList, List<String> typeList) {
        boolean isAnnouncement = cbAnnouncement.isChecked();
        String primaryUrl  = urlList.isEmpty() ? null : urlList.get(0);
        String primaryType = typeList.isEmpty() ? null : typeList.get(0);

        // Build JSON arrays for carousel
        String mediaUrlsJson  = null;
        String mediaTypesJson = null;
        if (urlList.size() > 1) {
            JSONArray ua = new JSONArray();
            JSONArray ta = new JSONArray();
            for (int i = 0; i < urlList.size(); i++) { ua.put(urlList.get(i)); ta.put(typeList.get(i)); }
            mediaUrlsJson  = ua.toString();
            mediaTypesJson = ta.toString();
        }

        String mentionsStr = pendingMentionedUids.isEmpty() ? null :
                android.text.TextUtils.join(",", pendingMentionedUids);

        long scheduledAt = scheduledCal != null ? scheduledCal.getTimeInMillis() : 0L;

        repo.publishPost(communityId, currentUid, currentName, currentPhoto,
                text, primaryUrl, primaryType, isAnnouncement,
                mediaUrlsJson, mediaTypesJson,
                mentionsStr, scheduledAt,
                (success, error) -> runOnUiThread(() -> {
                    btnPost.setEnabled(true);
                    if (success) {
                        Toast.makeText(this, "Posted!", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                    }
                }));
    }
}
