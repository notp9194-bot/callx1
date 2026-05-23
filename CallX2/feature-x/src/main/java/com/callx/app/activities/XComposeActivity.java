package com.callx.app.activities;

  import android.content.Intent;
  import android.net.Uri;
  import android.os.Bundle;
  import android.provider.MediaStore;
  import android.text.Editable;
  import android.text.TextWatcher;
  import android.view.View;
  import android.widget.EditText;
  import android.widget.ImageButton;
  import android.widget.ImageView;
  import android.widget.ProgressBar;
  import android.widget.TextView;
  import android.widget.Toast;
  import androidx.activity.result.ActivityResultLauncher;
  import androidx.activity.result.contract.ActivityResultContracts;
  import androidx.appcompat.app.AppCompatActivity;
  import com.bumptech.glide.Glide;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XCloudinaryUtils;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.ValueEventListener;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.regex.Matcher;
  import java.util.regex.Pattern;
  import de.hdodenhof.circleimageview.CircleImageView;

  public class XComposeActivity extends AppCompatActivity {

      private static final int MAX_CHARS = 280;

      private CircleImageView ivMyAvatar;
      private EditText etTweetText;
      private TextView tvCharCount;
      private ImageView ivPreviewMedia;
      private ProgressBar pbUpload;
      private View btnPost;
      private View btnMedia, btnGif, btnPoll, btnEmoji, btnSchedule;

      private String replyToId;
      private String replyToHandle;
      private String uploadedMediaUrl;
      private String uploadedMediaType;
      private String myUid, myName, myHandle, myPhotoUrl;
      private boolean isPosting;

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

          ivMyAvatar   = findViewById(R.id.iv_compose_avatar);
          etTweetText  = findViewById(R.id.et_compose_text);
          tvCharCount  = findViewById(R.id.tv_char_count);
          ivPreviewMedia = findViewById(R.id.iv_compose_media_preview);
          pbUpload     = findViewById(R.id.pb_compose_upload);
          btnPost      = findViewById(R.id.btn_x_post);
          btnMedia     = findViewById(R.id.btn_compose_media);
          btnGif       = findViewById(R.id.btn_compose_gif);
          btnPoll      = findViewById(R.id.btn_compose_poll);
          btnEmoji     = findViewById(R.id.btn_compose_emoji);
          btnSchedule  = findViewById(R.id.btn_compose_schedule);

          // Header
          findViewById(R.id.btn_compose_close).setOnClickListener(v -> finish());

          // Pre-fill reply
          if (replyToHandle != null) {
              etTweetText.setText("@" + replyToHandle + " ");
              etTweetText.setSelection(etTweetText.getText().length());
          }

          etTweetText.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
              @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateCharCount(); }
              @Override public void afterTextChanged(Editable s) {}
          });

          btnMedia.setOnClickListener(v -> pickMedia());
          btnPost.setOnClickListener(v -> postTweet());

          // Initialize uid immediately (sync) — don't wait for profile load
          myUid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          loadMyProfile();
      }

      private void loadMyProfile() {
          myUid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
          if (myUid.isEmpty()) return;
          XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  myName     = snap.child("name").getValue(String.class);
                  myHandle   = snap.child("handle").getValue(String.class);
                  myPhotoUrl = snap.child("photoUrl").getValue(String.class);
                  if (myPhotoUrl != null) Glide.with(XComposeActivity.this).load(myPhotoUrl)
                      .circleCrop().into(ivMyAvatar);
              }
              @Override public void onCancelled(DatabaseError e) {}
          });
      }

      private void updateCharCount() {
          int len = etTweetText.getText().length();
          int remaining = MAX_CHARS - len;
          tvCharCount.setText(String.valueOf(remaining));
          tvCharCount.setTextColor(remaining < 20
              ? getColor(R.color.x_like_active)
              : getColor(R.color.x_text_secondary));
          btnPost.setEnabled(len > 0 && len <= MAX_CHARS && !isPosting);
      }

      private void pickMedia() {
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
                      ivPreviewMedia.setVisibility(View.VISIBLE);
                      Glide.with(XComposeActivity.this).load(secureUrl).centerCrop().into(ivPreviewMedia);
                  });
              }
              @Override public void onError(String message) {
                  runOnUiThread(() -> {
                      pbUpload.setVisibility(View.GONE);
                      Toast.makeText(XComposeActivity.this, "Upload failed: " + message, Toast.LENGTH_SHORT).show();
                  });
              }
              @Override public void onProgress(int percent) {
                  runOnUiThread(() -> pbUpload.setProgress(percent));
              }
          };
          if (isVideo) XCloudinaryUtils.uploadTweetVideo(this, uri, cb);
          else         XCloudinaryUtils.uploadTweetImage(this, uri, cb);
      }

      private void postTweet() {
          String text = etTweetText.getText().toString().trim();
          if (text.isEmpty() || isPosting) return;

          // Guard: profile must be loaded
          if (myUid == null || myUid.isEmpty()) {
              Toast.makeText(this, "Please wait, loading profile...", Toast.LENGTH_SHORT).show();
              return;
          }

          isPosting = true;
          btnPost.setEnabled(false);

          XTweet tweet = new XTweet();
          tweet.authorUid      = myUid;
          tweet.authorName     = myName != null ? myName : "User";
          tweet.authorHandle   = myHandle != null ? myHandle : "user";
          tweet.authorPhotoUrl = myPhotoUrl != null ? myPhotoUrl : "";
          tweet.text           = text;
          tweet.timestamp      = System.currentTimeMillis();
          tweet.mediaUrl       = uploadedMediaUrl;
          tweet.mediaType      = uploadedMediaType;
          tweet.replyToTweetId = replyToId;
          tweet.hashtags       = extractHashtags(text);
          tweet.mentions       = extractMentions(text);

          String key = XFirebaseUtils.tweetsRef().push().getKey();
          if (key == null) { isPosting = false; btnPost.setEnabled(true); return; }
          tweet.id = key;

          XFirebaseUtils.tweetRef(key).setValue(tweet).addOnCompleteListener(t -> {
              if (t.isSuccessful()) {
                  // Publish to global feed
                  XFirebaseUtils.globalFeedRef().child(key).setValue(tweet);
                  // Save to user's own tweet list
                  XFirebaseUtils.userTweetsRef(myUid).child(key).setValue(true);
                  // If reply, record under parent thread
                  if (replyToId != null)
                      XFirebaseUtils.tweetRepliesRef(replyToId).child(key).setValue(true);
                  // Fan-out to followers' feeds
                  fanOutToFollowers(key, tweet);
                  Toast.makeText(this, "Posted!", Toast.LENGTH_SHORT).show();
                  finish();
              } else {
                  isPosting = false;
                  btnPost.setEnabled(true);
                  String errMsg = t.getException() != null ? t.getException().getMessage() : "Unknown error";
                  Toast.makeText(this, "Post failed: " + errMsg, Toast.LENGTH_LONG).show();
              }
          });
      }

      private void fanOutToFollowers(String tweetId, XTweet tweet) {
          XFirebaseUtils.userFollowersRef(myUid).get()
              .addOnSuccessListener(snap -> {
                  for (DataSnapshot ds : snap.getChildren()) {
                      String followerId = ds.getKey();
                      if (followerId != null)
                          XFirebaseUtils.userFeedRef(followerId).child(tweetId).setValue(tweet);
                  }
              });
      }

      private List<String> extractHashtags(String text) {
          List<String> tags = new ArrayList<>();
          Matcher m = Pattern.compile("#\\w+").matcher(text);
          while (m.find()) tags.add(m.group().toLowerCase());
          return tags;
      }

      private List<String> extractMentions(String text) {
          List<String> mentions = new ArrayList<>();
          Matcher m = Pattern.compile("@\\w+").matcher(text);
          while (m.find()) mentions.add(m.group().substring(1).toLowerCase());
          return mentions;
      }
  }