package com.callx.app.activities;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.XPoll;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XComposeActivity extends AppCompatActivity {

    private static final int MAX_CHARS = 280;

    private CircleImageView ivMyAvatar;
    private EditText etTweetText;
    private TextView tvCharCount;
    private ImageView ivPreviewMedia;
    private ProgressBar pbUpload;
    private View btnPost;

    // Quote tweet preview
    private View cardQuotePreview;
    private TextView tvQuotePreviewName, tvQuotePreviewText;

    // Poll views
    private LinearLayout llPollEditor;
    private EditText etPollOpt1, etPollOpt2, etPollOpt3, etPollOpt4;
    private View btnRemovePoll;

    private String replyToId, replyToHandle;
    private String quotedTweetId;
    private String uploadedMediaUrl, uploadedMediaType;
    private String myUid, myName, myHandle, myPhotoUrl, myThumbUrl;
    private boolean isPosting;
    private long scheduledAt = 0; // 0 = post now
    private boolean pollEnabled = false;

    private final ActivityResultLauncher<Intent> mediaPicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) uploadMedia(uri);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_compose);

        replyToId     = getIntent().getStringExtra("reply_to_id");
        replyToHandle = getIntent().getStringExtra("reply_to_handle");
        quotedTweetId = getIntent().getStringExtra("quote_tweet_id");

        ivMyAvatar      = findViewById(R.id.iv_compose_avatar);
        etTweetText     = findViewById(R.id.et_compose_text);
        tvCharCount     = findViewById(R.id.tv_char_count);
        ivPreviewMedia  = findViewById(R.id.iv_compose_media_preview);
        pbUpload        = findViewById(R.id.pb_compose_upload);
        btnPost         = findViewById(R.id.btn_x_post);
        cardQuotePreview= findViewById(R.id.card_compose_quote_preview);
        tvQuotePreviewName  = findViewById(R.id.tv_compose_quote_name);
        tvQuotePreviewText  = findViewById(R.id.tv_compose_quote_text);
        llPollEditor    = findViewById(R.id.ll_compose_poll_editor);
        etPollOpt1      = findViewById(R.id.et_poll_opt1);
        etPollOpt2      = findViewById(R.id.et_poll_opt2);
        etPollOpt3      = findViewById(R.id.et_poll_opt3);
        etPollOpt4      = findViewById(R.id.et_poll_opt4);
        btnRemovePoll   = findViewById(R.id.btn_remove_poll);

        // Pre-fill reply handle
        if (replyToHandle != null) {
            etTweetText.setText("@" + replyToHandle + " ");
            etTweetText.setSelection(etTweetText.getText().length());
        }

        // Load quote tweet preview
        if (quotedTweetId != null && !quotedTweetId.isEmpty()) {
            if (cardQuotePreview != null) cardQuotePreview.setVisibility(View.VISIBLE);
            XFirebaseUtils.tweetRef(quotedTweetId).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        com.callx.app.models.XTweet q = snap.getValue(com.callx.app.models.XTweet.class);
                        if (q != null) {
                            if (tvQuotePreviewName != null) tvQuotePreviewName.setText("@" + q.authorHandle);
                            if (tvQuotePreviewText != null) tvQuotePreviewText.setText(q.text);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        // Header
        findViewById(R.id.btn_compose_close).setOnClickListener(v -> finish());

        // Char counter
        etTweetText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateCharCount(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Action bar buttons
        View btnMedia    = findViewById(R.id.btn_compose_media);
        View btnGif      = findViewById(R.id.btn_compose_gif);
        View btnPoll     = findViewById(R.id.btn_compose_poll);
        View btnEmoji    = findViewById(R.id.btn_compose_emoji);
        View btnSchedule = findViewById(R.id.btn_compose_schedule);

        if (btnMedia    != null) btnMedia.setOnClickListener(v -> pickMedia());
        if (btnGif      != null) btnGif.setOnClickListener(v ->
            Toast.makeText(this, "GIF picker — coming in next update", Toast.LENGTH_SHORT).show());
        if (btnEmoji    != null) btnEmoji.setOnClickListener(v ->
            Toast.makeText(this, "Use your keyboard's emoji button", Toast.LENGTH_SHORT).show());
        if (btnPoll     != null) btnPoll.setOnClickListener(v -> togglePollEditor());
        if (btnSchedule != null) btnSchedule.setOnClickListener(v -> showSchedulePicker());

        if (btnRemovePoll != null) btnRemovePoll.setOnClickListener(v -> {
            pollEnabled = false;
            if (llPollEditor != null) llPollEditor.setVisibility(View.GONE);
        });

        btnPost.setOnClickListener(v -> postTweet());

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        loadMyProfile();
    }

    private void togglePollEditor() {
        pollEnabled = !pollEnabled;
        if (llPollEditor != null)
            llPollEditor.setVisibility(pollEnabled ? View.VISIBLE : View.GONE);
        // Disable media when poll is active
        if (pollEnabled) {
            uploadedMediaUrl = null;
            if (ivPreviewMedia != null) ivPreviewMedia.setVisibility(View.GONE);
        }
    }

    private void showSchedulePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, day) -> {
            new TimePickerDialog(this, (view2, hour, min) -> {
                Calendar sched = Calendar.getInstance();
                sched.set(year, month, day, hour, min, 0);
                scheduledAt = sched.getTimeInMillis();
                Toast.makeText(this,
                    "Scheduled for " + new java.text.SimpleDateFormat(
                        "MMM d, HH:mm", Locale.US).format(new Date(scheduledAt)),
                    Toast.LENGTH_SHORT).show();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadMyProfile() {
        if (myUid.isEmpty()) return;
        FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                myName     = snap.child("name").getValue(String.class);
                String mobile = snap.child("mobile").getValue(String.class);
                myHandle   = (mobile != null && !mobile.isEmpty()) ? mobile : "user";
                myPhotoUrl = snap.child("photoUrl").getValue(String.class);
                myThumbUrl = snap.child("thumbUrl").getValue(String.class);
                String avatarUrl = (myThumbUrl != null && !myThumbUrl.isEmpty()) ? myThumbUrl : myPhotoUrl;
                if (avatarUrl != null && !avatarUrl.isEmpty())
                    Glide.with(XComposeActivity.this).load(avatarUrl).circleCrop().into(ivMyAvatar);
                // Also ensure xUser record exists
                XFirebaseUtils.xUserRef(myUid).get().addOnSuccessListener(ds -> {
                    if (!ds.exists()) {
                        com.callx.app.models.XUser u = new com.callx.app.models.XUser();
                        u.uid     = myUid;
                        u.name    = myName != null ? myName : "User";
                        u.handle  = myHandle;
                        u.photoUrl= myPhotoUrl != null ? myPhotoUrl : "";
                        u.thumbUrl= myThumbUrl != null ? myThumbUrl : "";
                        u.joinedTs= System.currentTimeMillis();
                        XFirebaseUtils.xUserRef(myUid).setValue(u);
                    }
                });
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void updateCharCount() {
        int remaining = MAX_CHARS - etTweetText.getText().length();
        tvCharCount.setText(String.valueOf(remaining));
        tvCharCount.setTextColor(remaining < 20
            ? getColor(R.color.x_like_active)
            : getColor(R.color.x_text_secondary));
        btnPost.setEnabled(etTweetText.getText().length() > 0
            && etTweetText.getText().length() <= MAX_CHARS && !isPosting);
    }

    private void pickMedia() {
        if (pollEnabled) {
            Toast.makeText(this, "Remove the poll first to attach media", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/* video/*");
        mediaPicker.launch(intent);
    }

    private void uploadMedia(Uri uri) {
        pbUpload.setVisibility(View.VISIBLE);
        String type = getContentResolver().getType(uri);
        boolean isVideo = type != null && type.startsWith("video");
        XCloudinaryUtils.XUploadListener cb = new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String publicId, String secureUrl) {
                runOnUiThread(() -> {
                    uploadedMediaUrl  = secureUrl;
                    uploadedMediaType = isVideo ? "video" : "image";
                    pbUpload.setVisibility(View.GONE);
                    if (ivPreviewMedia != null) {
                        ivPreviewMedia.setVisibility(View.VISIBLE);
                        Glide.with(XComposeActivity.this).load(secureUrl).centerCrop().into(ivPreviewMedia);
                    }
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    pbUpload.setVisibility(View.GONE);
                    Toast.makeText(XComposeActivity.this, "Upload failed: " + msg, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onProgress(int pct) {
                runOnUiThread(() -> { if (pbUpload instanceof ProgressBar) pbUpload.setProgress(pct); });
            }
        };
        if (isVideo) XCloudinaryUtils.uploadTweetVideo(this, uri, cb);
        else         XCloudinaryUtils.uploadTweetImage(this, uri, cb);
    }

    private void postTweet() {
        String text = etTweetText.getText().toString().trim();
        if (text.isEmpty() || isPosting || myUid.isEmpty()) return;

        isPosting = true;
        btnPost.setEnabled(false);

        XTweet tweet = new XTweet();
        tweet.authorUid      = myUid;
        tweet.authorName     = myName != null ? myName : "User";
        tweet.authorHandle   = myHandle != null ? myHandle : "user";
        tweet.authorPhotoUrl = (myThumbUrl != null && !myThumbUrl.isEmpty()) ? myThumbUrl
                             : (myPhotoUrl != null ? myPhotoUrl : "");
        tweet.text           = text;
        tweet.timestamp      = System.currentTimeMillis();
        tweet.scheduledAt    = scheduledAt;
        tweet.mediaUrl       = uploadedMediaUrl;
        tweet.mediaType      = uploadedMediaType;
        tweet.replyToTweetId = replyToId;
        tweet.quotedTweetId  = quotedTweetId;
        tweet.hashtags       = extractHashtags(text);
        tweet.mentions       = extractMentions(text);

        // Build poll if enabled
        XPoll poll = null;
        if (pollEnabled) {
            poll = buildPoll();
            if (poll == null) {
                isPosting = false; btnPost.setEnabled(true);
                Toast.makeText(this, "Add at least 2 poll options", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String key = XFirebaseUtils.tweetsRef().push().getKey();
        if (key == null) { isPosting = false; btnPost.setEnabled(true); return; }
        tweet.id = key;
        if (poll != null) { poll.tweetId = key; tweet.pollId = key; }

        final XPoll finalPoll = poll;
        XFirebaseUtils.tweetRef(key).setValue(tweet).addOnCompleteListener(t -> {
            if (t.isSuccessful()) {
                if (finalPoll != null) XFirebaseUtils.tweetPollRef(key).setValue(finalPoll);
                if (scheduledAt <= 0) {
                    XFirebaseUtils.globalFeedRef().child(key).setValue(tweet);
                }
                XFirebaseUtils.userTweetsRef(myUid).child(key).setValue(true);
                if (replyToId != null)
                    XFirebaseUtils.tweetRepliesRef(replyToId).child(key).setValue(true);
                if (scheduledAt <= 0) fanOutToFollowers(key, tweet);
                // Index hashtags + update trending counters
                long nowTs = System.currentTimeMillis();
                for (String tag : tweet.hashtags != null ? tweet.hashtags : new java.util.ArrayList<String>()) {
                    String clean = tag.replace("#","").toLowerCase(Locale.US);
                    String display = tag.startsWith("#") ? tag : "#" + tag;
                    // Write to hashtag feed index
                    XFirebaseUtils.hashtagFeedRef(clean).child(key).setValue(nowTs);
                    // Atomically increment trending counter
                    XFirebaseUtils.trendingTagRef(clean).get().addOnSuccessListener(tSnap -> {
                        long curCount = tSnap.child("countAll").getValue(Long.class) != null
                            ? tSnap.child("countAll").getValue(Long.class) : 0L;
                        long cur24h = tSnap.child("count24h").getValue(Long.class) != null
                            ? tSnap.child("count24h").getValue(Long.class) : 0L;
                        java.util.Map<String, Object> trendData = new java.util.HashMap<>();
                        trendData.put("displayTag", display);
                        trendData.put("cleanTag",   clean);
                        trendData.put("countAll",   curCount + 1);
                        trendData.put("count24h",   cur24h + 1);
                        trendData.put("lastPostAt", nowTs);
                        XFirebaseUtils.trendingTagRef(clean).updateChildren(trendData);
                    });
                }
                Toast.makeText(this,
                    scheduledAt > 0 ? "Scheduled!" : "Posted!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                isPosting = false; btnPost.setEnabled(true);
                String err = t.getException() != null ? t.getException().getMessage() : "Unknown";
                Toast.makeText(this, "Post failed: " + err, Toast.LENGTH_LONG).show();
            }
        });
    }

    private XPoll buildPoll() {
        List<String> opts = new ArrayList<>();
        if (etPollOpt1 != null && !etPollOpt1.getText().toString().trim().isEmpty())
            opts.add(etPollOpt1.getText().toString().trim());
        if (etPollOpt2 != null && !etPollOpt2.getText().toString().trim().isEmpty())
            opts.add(etPollOpt2.getText().toString().trim());
        if (etPollOpt3 != null && !etPollOpt3.getText().toString().trim().isEmpty())
            opts.add(etPollOpt3.getText().toString().trim());
        if (etPollOpt4 != null && !etPollOpt4.getText().toString().trim().isEmpty())
            opts.add(etPollOpt4.getText().toString().trim());
        if (opts.size() < 2) return null;
        XPoll p = new XPoll();
        p.options    = opts;
        p.expiresAt  = System.currentTimeMillis() + 24 * 3600_000L; // 24h default
        p.expired    = false;
        return p;
    }

    private void fanOutToFollowers(String tweetId, XTweet tweet) {
        XFirebaseUtils.userFollowersRef(myUid).get().addOnSuccessListener(snap -> {
            for (DataSnapshot ds : snap.getChildren()) {
                String fid = ds.getKey();
                if (fid != null) XFirebaseUtils.userFeedRef(fid).child(tweetId).setValue(tweet);
            }
        });
    }

    private List<String> extractHashtags(String text) {
        List<String> tags = new ArrayList<>();
        Matcher m = Pattern.compile("#\\w+").matcher(text);
        while (m.find()) tags.add(m.group().toLowerCase(Locale.US));
        return tags;
    }

    private List<String> extractMentions(String text) {
        List<String> mentions = new ArrayList<>();
        Matcher m = Pattern.compile("@\\w+").matcher(text);
        while (m.find()) mentions.add(m.group().substring(1).toLowerCase(Locale.US));
        return mentions;
    }
}
