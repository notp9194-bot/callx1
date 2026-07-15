package com.callx.app.community;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

/**
 * CommunityPostComposerActivity — text + optional single image + optional
 * poll (up to 4 options, dynamic list per activity_community_post_composer.xml).
 * canAnnounce/isAnnouncement come from the launching CommunityActivity, which
 * already knows the current user's role — this screen just presents/enforces
 * a toggle when the option is available (admin/owner only).
 */
public class CommunityPostComposerActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_IS_ANNOUNCEMENT = "isAnnouncement";
    public static final String EXTRA_CAN_ANNOUNCE = "canAnnounce";

    private static final int MAX_POLL_OPTIONS = 4;

    private String communityId, currentUid, myName, myPhoto;
    private boolean isAnnouncement;
    private boolean canAnnounce;
    private Uri pickedImageUri;

    private EditText etPostText, etPollQuestion;
    private View containerImagePreview;
    private ImageView ivImagePreview;
    private View btnRemoveImage, btnAttachPhoto, btnTogglePoll, btnAddPollOption, btnSubmit;
    private View layoutPoll;
    private LinearLayout layoutPollOptions;

    private CommunityRepository repo;
    private ActivityResultLauncher<String> imagePicker;
    private boolean pollEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_post_composer);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        isAnnouncement = getIntent().getBooleanExtra(EXTRA_IS_ANNOUNCEMENT, false);
        canAnnounce = getIntent().getBooleanExtra(EXTRA_CAN_ANNOUNCE, false);
        repo = CommunityRepository.getInstance(this);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
            Uri photo = FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl();
            myPhoto = photo != null ? photo.toString() : null;
        }

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(isAnnouncement ? "New Announcement" : "New Post");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindViews();

        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            pickedImageUri = uri;
            containerImagePreview.setVisibility(View.VISIBLE);
            Glide.with(this).load(uri).into(ivImagePreview);
        });

        btnAttachPhoto.setOnClickListener(v -> imagePicker.launch("image/*"));
        btnRemoveImage.setOnClickListener(v -> {
            pickedImageUri = null;
            containerImagePreview.setVisibility(View.GONE);
        });

        btnTogglePoll.setOnClickListener(v -> {
            pollEnabled = !pollEnabled;
            layoutPoll.setVisibility(pollEnabled ? View.VISIBLE : View.GONE);
            if (pollEnabled && layoutPollOptions.getChildCount() == 0) {
                addPollOptionRow();
                addPollOptionRow();
            }
        });

        btnAddPollOption.setOnClickListener(v -> {
            if (layoutPollOptions.getChildCount() < MAX_POLL_OPTIONS) addPollOptionRow();
            else Toast.makeText(this, "Max " + MAX_POLL_OPTIONS + " options", Toast.LENGTH_SHORT).show();
        });

        btnSubmit.setOnClickListener(v -> submit());
    }

    private void bindViews() {
        etPostText            = findViewById(R.id.et_post_text);
        containerImagePreview = findViewById(R.id.container_image_preview);
        ivImagePreview        = findViewById(R.id.iv_image_preview);
        btnRemoveImage        = findViewById(R.id.btn_remove_image);
        btnAttachPhoto        = findViewById(R.id.btn_attach_photo);
        btnTogglePoll         = findViewById(R.id.btn_toggle_poll);
        layoutPoll            = findViewById(R.id.layout_poll);
        etPollQuestion        = findViewById(R.id.et_poll_question);
        layoutPollOptions     = findViewById(R.id.layout_poll_options);
        btnAddPollOption      = findViewById(R.id.btn_add_poll_option);
        btnSubmit             = findViewById(R.id.btn_submit);
    }

    private void addPollOptionRow() {
        EditText et = new EditText(this);
        et.setHint("Option " + (layoutPollOptions.getChildCount() + 1));
        et.setTextColor(0xFF1A1A1A);
        et.setHintTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        et.setLayoutParams(lp);
        layoutPollOptions.addView(et);
    }

    private void submit() {
        String text = etPostText.getText().toString().trim();
        CommunityPoll poll = buildPollOrNull();

        if (text.isEmpty() && pickedImageUri == null && poll == null) {
            Toast.makeText(this, "Write something, attach a photo, or add a poll", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isAnnouncement && !canAnnounce) {
            Toast.makeText(this, "Only admins can post announcements", Toast.LENGTH_SHORT).show();
            return;
        }
        if (communityId == null || currentUid == null) return;

        btnSubmit.setEnabled(false);

        if (pickedImageUri != null) {
            CloudinaryUploader.upload(this, pickedImageUri, "callx/community_posts", "image",
                    new CloudinaryUploader.UploadCallback() {
                        @Override public void onSuccess(CloudinaryUploader.Result result) {
                            runOnUiThread(() -> doCreatePost(text, result.secureUrl, "image", poll));
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> {
                                btnSubmit.setEnabled(true);
                                Toast.makeText(CommunityPostComposerActivity.this,
                                        "Image upload failed: " + message, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        } else {
            doCreatePost(text, null, null, poll);
        }
    }

    @androidx.annotation.Nullable
    private CommunityPoll buildPollOrNull() {
        if (!pollEnabled) return null;
        String question = etPollQuestion.getText().toString().trim();
        List<String> options = new ArrayList<>();
        for (int i = 0; i < layoutPollOptions.getChildCount(); i++) {
            View child = layoutPollOptions.getChildAt(i);
            if (child instanceof EditText) {
                String opt = ((EditText) child).getText().toString().trim();
                if (!opt.isEmpty()) options.add(opt);
            }
        }
        if (question.isEmpty() || options.size() < 2) {
            return null; // silently skipped — submit() already validates there's other content
        }
        return new CommunityPoll(question, options);
    }

    private void doCreatePost(String text, String mediaUrl, String mediaType, CommunityPoll poll) {
        repo.createPost(communityId, currentUid, myName, myPhoto, text, mediaUrl, mediaType,
                isAnnouncement, poll, (success, error) -> {
                    runOnUiThread(() -> {
                        btnSubmit.setEnabled(true);
                        if (success) {
                            finish();
                        } else {
                            Toast.makeText(this, "Failed to post: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                });
    }
}
