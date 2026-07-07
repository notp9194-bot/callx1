package com.callx.app.conversation;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MessageInfoActivity — Dedicated "Message info" screen for 1-on-1 chat.
 *
 * Replaces the old AlertDialog in ChatActivity#showMessageInfoDialog.
 * Displays the message preview (text bubble / image / video / media-group /
 * audio / file) at the top and the delivery-status rows below it:
 *
 *   ✓  Sent        — grey  single tick  (always shown)
 *   ✓✓ Delivered   — grey  double tick  (shown when status = delivered or read)
 *   ✓✓ Seen        — blue  double tick  (shown when status = read)
 *
 * Intent extras expected (all optional — graceful fallbacks applied):
 *   msg_text        (String)  — message body text
 *   msg_type        (String)  — "text" | "image" | "video" | "audio" | "file"
 *                               | "media_group" | "sticker" | …
 *   msg_status      (String)  — "sent" | "delivered" | "read"
 *   msg_timestamp   (long)    — epoch ms when the message was sent
 *   msg_delivered_at(long)    — epoch ms of delivery  (0 = unknown)
 *   msg_read_at     (long)    — epoch ms of read      (0 = unknown)
 *   msg_media_url   (String)  — URL for image / video / sticker thumbnail
 *   msg_thumbnail_url(String) — thumbnail URL for video (fallback to mediaUrl)
 *   msg_media_count (int)     — number of items in a media_group (0 = single)
 *   msg_forwarded_from(String)— non-empty when the message was forwarded
 */
public class MessageInfoActivity extends AppCompatActivity {

    // ── Intent extra keys ────────────────────────────────────────────────
    public static final String EXTRA_TEXT          = "msg_text";
    public static final String EXTRA_TYPE          = "msg_type";
    public static final String EXTRA_STATUS        = "msg_status";
    public static final String EXTRA_TIMESTAMP     = "msg_timestamp";
    public static final String EXTRA_DELIVERED_AT  = "msg_delivered_at";
    public static final String EXTRA_READ_AT       = "msg_read_at";
    public static final String EXTRA_MEDIA_URL     = "msg_media_url";
    public static final String EXTRA_THUMBNAIL_URL = "msg_thumbnail_url";
    public static final String EXTRA_MEDIA_COUNT   = "msg_media_count";
    public static final String EXTRA_FORWARDED_FROM= "msg_forwarded_from";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    // ── Views ────────────────────────────────────────────────────────────
    // preview area
    private LinearLayout llTextBubble;
    private TextView     tvForwardedLabel;
    private LinearLayout llFileRow;
    private TextView     tvFileIcon;
    private TextView     tvFileLabel;
    private TextView     tvMsgText;
    private FrameLayout  flMediaPreview;
    private ImageView    ivMsgMedia;
    private ImageView    ivPlayOverlay;
    private TextView     tvMediaCountBadge;
    private TextView     tvMediaForwarded;

    // status rows
    private LinearLayout llRowSeen;
    private TextView     tvSeenTime;
    private View         dividerSeenDelivered;
    private LinearLayout llRowDelivered;
    private TextView     tvDeliveredTime;
    private View         dividerDeliveredSent;

    // sent row (always visible — no reference needed for visibility)
    private TextView tvSentTime;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_info);

        // ── Toolbar ───────────────────────────────────────────────────
        Toolbar toolbar = findViewById(R.id.toolbar_msg_info);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        // ── Bind views ────────────────────────────────────────────────
        llTextBubble        = findViewById(R.id.ll_text_bubble);
        tvForwardedLabel    = findViewById(R.id.tv_forwarded_label);
        llFileRow           = findViewById(R.id.ll_file_row);
        tvFileIcon          = findViewById(R.id.tv_file_icon);
        tvFileLabel         = findViewById(R.id.tv_file_label);
        tvMsgText           = findViewById(R.id.tv_msg_text);
        flMediaPreview      = findViewById(R.id.fl_media_preview);
        ivMsgMedia          = findViewById(R.id.iv_msg_media);
        ivPlayOverlay       = findViewById(R.id.iv_play_overlay);
        tvMediaCountBadge   = findViewById(R.id.tv_media_count_badge);
        tvMediaForwarded    = findViewById(R.id.tv_media_forwarded);
        llRowSeen           = findViewById(R.id.ll_row_seen);
        tvSeenTime          = findViewById(R.id.tv_seen_time);
        dividerSeenDelivered= findViewById(R.id.divider_seen_delivered);
        llRowDelivered      = findViewById(R.id.ll_row_delivered);
        tvDeliveredTime     = findViewById(R.id.tv_delivered_time);
        dividerDeliveredSent= findViewById(R.id.divider_delivered_sent);
        tvSentTime          = findViewById(R.id.tv_sent_time);

        // ── Read intent extras ────────────────────────────────────────
        Intent intent        = getIntent();
        String type          = intent.getStringExtra(EXTRA_TYPE);          if (type == null)          type          = "text";
        String status        = intent.getStringExtra(EXTRA_STATUS);        if (status == null)        status        = "sent";
        String text          = intent.getStringExtra(EXTRA_TEXT);          if (text == null)          text          = "";
        String mediaUrl      = intent.getStringExtra(EXTRA_MEDIA_URL);     if (mediaUrl == null)      mediaUrl      = "";
        String thumbnailUrl  = intent.getStringExtra(EXTRA_THUMBNAIL_URL); if (thumbnailUrl == null)  thumbnailUrl  = "";
        String forwardedFrom = intent.getStringExtra(EXTRA_FORWARDED_FROM);if (forwardedFrom == null) forwardedFrom = "";
        long   timestamp     = intent.getLongExtra(EXTRA_TIMESTAMP,    0L);
        long   deliveredAt   = intent.getLongExtra(EXTRA_DELIVERED_AT, 0L);
        long   readAt        = intent.getLongExtra(EXTRA_READ_AT,      0L);
        int    mediaCount    = intent.getIntExtra (EXTRA_MEDIA_COUNT,  0);

        // ── Populate message preview ──────────────────────────────────
        populatePreview(type, text, mediaUrl, thumbnailUrl, forwardedFrom, mediaCount);

        // ── Populate status rows ──────────────────────────────────────
        populateStatusRows(status, timestamp, deliveredAt, readAt);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PREVIEW
    // ─────────────────────────────────────────────────────────────────────

    private void populatePreview(String type, String text,
                                  String mediaUrl, String thumbnailUrl,
                                  String forwardedFrom, int mediaCount) {
        boolean isMediaType = isMediaType(type);

        if (isMediaType && !TextUtils.isEmpty(mediaUrl)) {
            showMediaPreview(type, mediaUrl, thumbnailUrl, forwardedFrom, mediaCount);
        } else {
            showTextBubble(type, text, forwardedFrom);
        }
    }

    private boolean isMediaType(String type) {
        switch (type) {
            case "image":
            case "video":
            case "sticker":
            case "media_group":
                return true;
            default:
                return false;
        }
    }

    private void showMediaPreview(String type, String mediaUrl, String thumbnailUrl,
                                   String forwardedFrom, int mediaCount) {
        flMediaPreview.setVisibility(View.VISIBLE);
        llTextBubble.setVisibility(View.GONE);

        // Load image/thumbnail
        String loadUrl = "video".equals(type) && !TextUtils.isEmpty(thumbnailUrl)
                ? thumbnailUrl : mediaUrl;
        Glide.with(this)
                .load(loadUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(ivMsgMedia);

        // Video play icon
        if ("video".equals(type)) {
            ivPlayOverlay.setVisibility(View.VISIBLE);
        }

        // Media-group count badge (e.g. "+3")
        if ("media_group".equals(type) && mediaCount > 1) {
            tvMediaCountBadge.setVisibility(View.VISIBLE);
            tvMediaCountBadge.setText("+" + (mediaCount - 1));
        }

        // Forwarded badge over media
        if (!TextUtils.isEmpty(forwardedFrom)) {
            tvMediaForwarded.setVisibility(View.VISIBLE);
        }
    }

    private void showTextBubble(String type, String text, String forwardedFrom) {
        llTextBubble.setVisibility(View.VISIBLE);
        flMediaPreview.setVisibility(View.GONE);

        // Forwarded label
        if (!TextUtils.isEmpty(forwardedFrom)) {
            tvForwardedLabel.setVisibility(View.VISIBLE);
        }

        // Audio / file icon row
        switch (type) {
            case "audio":
                llFileRow.setVisibility(View.VISIBLE);
                tvFileIcon.setText("🎵");
                tvFileLabel.setVisibility(View.GONE);
                break;
            case "file":
                llFileRow.setVisibility(View.VISIBLE);
                tvFileIcon.setText("📎");
                if (!TextUtils.isEmpty(text)) {
                    tvFileLabel.setVisibility(View.VISIBLE);
                    tvFileLabel.setText(text);
                    // Don't show text again below
                    text = "";
                }
                break;
            default:
                break;
        }

        // Body text
        if (!TextUtils.isEmpty(text)) {
            tvMsgText.setVisibility(View.VISIBLE);
            tvMsgText.setText(text);
        } else if (llFileRow.getVisibility() != View.VISIBLE) {
            // Nothing to show — show placeholder
            tvMsgText.setVisibility(View.VISIBLE);
            tvMsgText.setText("(no content)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // STATUS ROWS
    // ─────────────────────────────────────────────────────────────────────

    private void populateStatusRows(String status, long timestamp,
                                     long deliveredAt, long readAt) {
        boolean isDelivered = "delivered".equals(status) || "read".equals(status);
        boolean isRead      = "read".equals(status);

        // Sent (always)
        tvSentTime.setText(formatTs(timestamp));

        // Delivered row
        if (isDelivered) {
            llRowDelivered.setVisibility(View.VISIBLE);
            tvDeliveredTime.setText(formatTs(deliveredAt > 0 ? deliveredAt : timestamp));
        }

        // Seen row
        if (isRead) {
            llRowSeen.setVisibility(View.VISIBLE);
            tvSeenTime.setText(formatTs(readAt > 0 ? readAt : timestamp));
            dividerSeenDelivered.setVisibility(View.VISIBLE);
        }

        // Divider between delivered and sent (shown when delivered row is visible)
        if (isDelivered) {
            dividerDeliveredSent.setVisibility(View.VISIBLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private String formatTs(long epochMs) {
        if (epochMs <= 0) return "–";
        return DATE_FMT.format(new Date(epochMs));
    }
}
