package com.callx.app.social;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class StoryReshareActivity extends AppCompatActivity {

    public static final String EXTRA_CONTENT_TYPE = "EXTRA_CONTENT_TYPE";
    public static final String EXTRA_CONTENT_ID = "EXTRA_CONTENT_ID";
    public static final String EXTRA_OWNER_UID = "EXTRA_OWNER_UID";
    public static final String EXTRA_OWNER_NAME = "EXTRA_OWNER_NAME";
    public static final String EXTRA_OWNER_AVATAR = "EXTRA_OWNER_AVATAR";
    public static final String EXTRA_MEDIA_URL = "EXTRA_MEDIA_URL";
    public static final String EXTRA_THUMB_URL = "EXTRA_THUMB_URL";
    public static final String EXTRA_CAPTION = "EXTRA_CAPTION";
    public static final String EXTRA_MEDIA_TYPE = "EXTRA_MEDIA_TYPE";
    public static final String EXTRA_ALLOW_RESHARE = "EXTRA_ALLOW_RESHARE";

    private String contentType, contentId, ownerUid, ownerName, ownerAvatar, mediaUrl, thumbUrl, originalCaption, mediaType;
    private String myUid, myName, myPhoto = "";
    private String selectedColor = "#1A1A2E";

    private FrameLayout flPreview, flStickerContainer;
    private LinearLayout llBackground;
    private PlayerView playerView;
    private ImageView ivImage;
    private ExoPlayer player;
    private EditText etTextOverlay, etCaption;
    private ProgressBar progressBar;
    private Button btnAddStory;
    private HorizontalScrollView hsvColors;
    private LinearLayout llCaptionRow;

    private View cardSticker;
    private float dX, dY;
    private final String[] colorPresets = {"#1A1A2E", "#16213E", "#0F3460", "#533483", "#2D6A4F", "#C13584"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_story_reshare);

        myUid = FirebaseUtils.getCurrentUid();
        myName = FirebaseUtils.getCurrentName();
        if (myUid.isEmpty()) { finish(); return; }

        extractExtras();
        bindViews();
        setupMediaPreview();
        setupSticker();
        loadMyPhoto();
    }

    private void extractExtras() {
        contentType = getIntent().getStringExtra(EXTRA_CONTENT_TYPE);
        contentId = getIntent().getStringExtra(EXTRA_CONTENT_ID);
        ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);
        ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerAvatar = getIntent().getStringExtra(EXTRA_OWNER_AVATAR);
        mediaUrl = getIntent().getStringExtra(EXTRA_MEDIA_URL);
        thumbUrl = getIntent().getStringExtra(EXTRA_THUMB_URL);
        originalCaption = getIntent().getStringExtra(EXTRA_CAPTION);
        mediaType = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
    }

    private void bindViews() {
        findViewById(R.id.btn_reshare_back).setOnClickListener(v -> finish());
        flPreview = findViewById(R.id.fl_reshare_preview);
        flStickerContainer = findViewById(R.id.fl_card_sticker_container);
        llBackground = findViewById(R.id.ll_reshare_bg);
        playerView = findViewById(R.id.pv_reshare_video);
        ivImage = findViewById(R.id.iv_reshare_image);
        etTextOverlay = findViewById(R.id.et_reshare_text_overlay);
        etCaption = findViewById(R.id.et_reshare_caption);
        progressBar = findViewById(R.id.progress_reshare);
        btnAddStory = findViewById(R.id.btn_add_to_story);
        hsvColors = findViewById(R.id.hsv_bg_colors);
        llCaptionRow = findViewById(R.id.ll_caption_row);

        findViewById(R.id.btn_tool_text).setOnClickListener(v -> toggleTextTool());
        findViewById(R.id.btn_tool_bg).setOnClickListener(v -> toggleBgTool());
        findViewById(R.id.btn_tool_caption).setOnClickListener(v -> toggleCaptionTool());

        setupColorPickers();

        btnAddStory.setOnClickListener(v -> shareToStory());
    }

    private void setupColorPickers() {
        int[] ids = {R.id.bg_color_1, R.id.bg_color_2, R.id.bg_color_3, R.id.bg_color_4, R.id.bg_color_5, R.id.bg_color_6};
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            String color = colorPresets[i];
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.parseColor(color));
            v.setBackground(shape);
            v.setOnClickListener(view -> updateBackground(color));
        }
    }

    private void updateBackground(String color) {
        selectedColor = color;
        llBackground.setBackgroundColor(Color.parseColor(color));
    }

    private void setupMediaPreview() {
        if ("video".equalsIgnoreCase(mediaType)) {
            playerView.setVisibility(View.VISIBLE);
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(mediaUrl));
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.setVolume(0f);
            player.prepare();
            player.play();
        } else {
            ivImage.setVisibility(View.VISIBLE);
            Glide.with(this).load(mediaUrl).into(ivImage);
        }
    }

    private void setupSticker() {
        cardSticker = getLayoutInflater().inflate(com.callx.app.status.R.layout.item_status_card, flStickerContainer, false);
        ImageView ivThumb = cardSticker.findViewById(R.id.iv_status_thumb);
        TextView tvName = cardSticker.findViewById(R.id.tv_status_owner_name);
        TextView tvBadge = cardSticker.findViewById(R.id.tv_status_type_badge);
        
        if (ivThumb != null) Glide.with(this).load(thumbUrl).into(ivThumb);
        if (tvName != null) tvName.setText("@" + ownerName);
        if (tvBadge != null) tvBadge.setText(contentType.toUpperCase());

        cardSticker.setBackgroundResource(R.drawable.bg_reshare_card);
        cardSticker.setElevation(8f);
        
        flStickerContainer.addView(cardSticker);

        cardSticker.post(() -> {
            cardSticker.setTranslationX(flStickerContainer.getWidth() * 0.1f);
            cardSticker.setTranslationY(flStickerContainer.getHeight() * 0.35f);
        });

        cardSticker.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getTranslationX() - event.getRawX();
                        dY = view.getTranslationY() - event.getRawY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;
                        
                        newX = Math.max(0, Math.min(newX, flStickerContainer.getWidth() - view.getWidth()));
                        newY = Math.max(0, Math.min(newY, flStickerContainer.getHeight() - view.getHeight()));
                        
                        view.setTranslationX(newX);
                        view.setTranslationY(newY);
                        break;
                    default:
                        return false;
                }
                return true;
            }
        });
    }

    private void loadMyPhoto() {
        FirebaseDatabase.getInstance(Constants.DB_URL).getReference("reels/users").child(myUid).child("photoUrl")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        myPhoto = snapshot.getValue(String.class);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void toggleTextTool() {
        etTextOverlay.setVisibility(etTextOverlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (etTextOverlay.getVisibility() == View.VISIBLE) {
            etTextOverlay.requestFocus();
            hsvColors.setVisibility(View.GONE);
            llCaptionRow.setVisibility(View.GONE);
        }
    }

    private void toggleBgTool() {
        hsvColors.setVisibility(hsvColors.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (hsvColors.getVisibility() == View.VISIBLE) {
            etTextOverlay.clearFocus();
            llCaptionRow.setVisibility(View.GONE);
        }
    }

    private void toggleCaptionTool() {
        llCaptionRow.setVisibility(llCaptionRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (llCaptionRow.getVisibility() == View.VISIBLE) {
            etCaption.requestFocus();
            etTextOverlay.clearFocus();
            hsvColors.setVisibility(View.GONE);
        }
    }

    private void shareToStory() {
        progressBar.setVisibility(View.VISIBLE);
        btnAddStory.setEnabled(false);

        DatabaseReference ref = FirebaseDatabase.getInstance(Constants.DB_URL).getReference("status").child(myUid).push();
        String reshareId = ref.getKey();

        Map<String, Object> data = new HashMap<>();
        data.put("id", reshareId);
        data.put("type", "reel".equals(contentType) ? "reel_reshare" : "post_reshare");
        data.put("mediaUrl", mediaUrl);
        data.put("thumbnailUrl", thumbUrl);
        data.put("resharedFromType", contentType);
        data.put("resharedFromId", contentId);
        data.put("resharedFromOwnerUid", ownerUid);
        data.put("resharedFromOwnerName", ownerName);
        data.put("resharedFromOwnerAvatar", ownerAvatar);
        data.put("resharedThumbnailUrl", thumbUrl);
        data.put("attribution", "Originally posted by @" + ownerName);
        data.put("stickerText", etTextOverlay.getText().toString());
        data.put("caption", etCaption.getText().toString());
        data.put("cardStickerX", cardSticker.getTranslationX() / flStickerContainer.getWidth());
        data.put("cardStickerY", cardSticker.getTranslationY() / flStickerContainer.getHeight());
        data.put("reshareBackgroundColor", selectedColor);
        data.put("privacy", ((RadioButton)findViewById(R.id.rb_story_closefriends)).isChecked() ? "close_friends" : "everyone");
        data.put("timestamp", System.currentTimeMillis());
        data.put("expiresAt", System.currentTimeMillis() + 86400000L);
        data.put("ownerUid", myUid);
        data.put("ownerName", myName);
        data.put("ownerPhoto", myPhoto != null ? myPhoto : "");

        ref.setValue(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if ("reel".equals(contentType)) {
                    FirebaseDatabase.getInstance(Constants.DB_URL).getReference("reels").child(contentId).child("reshareCount")
                            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                                @NonNull @Override public com.google.firebase.database.Transaction.Result doTransaction(@NonNull com.google.firebase.database.MutableData currentData) {
                                    Integer count = currentData.getValue(Integer.class);
                                    currentData.setValue(count == null ? 1 : count + 1);
                                    return com.google.firebase.database.Transaction.success(currentData);
                                }
                                @Override public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {}
                            });
                }
                Toast.makeText(this, "Added to your story!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                btnAddStory.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "Failed to share story", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
