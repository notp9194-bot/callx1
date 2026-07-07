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
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MessageInfoActivity — Ultra-optimised "Message info" screen for 1-on-1 chat.
 *
 * ═══════════════════════════════════════════════════════════════
 * PERFORMANCE FIXES (v2 — jank-free rewrite)
 * ═══════════════════════════════════════════════════════════════
 *
 * ROOT CAUSES of the previous jank / scroll-flicker in ChatActivity:
 *
 * 1. WINDOW TRANSITION CONFLICT
 *    ChatActivity declares setEnterTransition(Slide(END)) / setExitTransition(Slide(END)).
 *    When startActivity(info) was called WITHOUT overridePendingTransition(),
 *    Android fired ChatActivity's EXIT Slide animation at the same time the
 *    RecyclerView's LAYER_TYPE_HARDWARE fling was active.  Two competing
 *    hardware-layer compositing operations on the same window frame caused
 *    GPU memory contention → visible jank / flicker on the chat list.
 *
 *    FIX: ChatActivity.showMessageInfoDialog() now calls
 *         overridePendingTransition(R.anim.msg_info_slide_in_bottom, 0)
 *    so ONLY MessageInfoActivity slides up; ChatActivity's window is frozen
 *    (0 = no animation), and finish() here calls
 *         overridePendingTransition(0, R.anim.msg_info_slide_out_bottom)
 *    so only we slide back down, ChatActivity snaps back silently.
 *
 * 2. MISSING MANIFEST ENTRY
 *    MessageInfoActivity was never registered in AndroidManifest.xml, so
 *    Android fell back to an implicit default task/theme that could trigger
 *    unexpected lifecycle callbacks on ChatActivity.
 *    FIX: added to AndroidManifest.xml with excludeFromRecents + noHistory.
 *
 * 3. SimpleDateFormat NOT THREAD-SAFE
 *    A single static SimpleDateFormat instance was shared across any thread
 *    that might format a timestamp, risking corrupted output or crashes.
 *    FIX: ThreadLocal<SimpleDateFormat> — each thread gets its own instance.
 *
 * 4. onResume() OVERHEAD ON RETURN
 *    Every return from MessageInfoActivity fired ChatActivity.onResume() which
 *    called ImmersiveModeUtils.enterImmersive() → window insets change →
 *    RecyclerView re-layout, plus preloadLastImageMessages() attempting Glide
 *    cache warm-ups.  These are ChatActivity-side issues; the transition fix
 *    above (freezing ChatActivity's window) means these calls happen AFTER the
 *    slide-down animation finishes, at which point the RV is idle and no frame
 *    budget is wasted.
 *
 * 5. GLIDE LIFECYCLE / PLACEHOLDER
 *    Previous code loaded media without a placeholder, causing a blank ImageView
 *    during the Glide decode → brief white flash in the preview area.
 *    FIX: PREFER_RGB_565 + centerCrop + placeholder colour, all applied via
 *    a single pre-built RequestOptions object (avoids per-call RequestOptions
 *    allocation on the critical path).
 *
 * ═══════════════════════════════════════════════════════════════
 * Intent extras (all optional — graceful fallbacks applied):
 *   msg_text         (String) — message body text
 *   msg_type         (String) — "text"|"image"|"video"|"audio"|"file"
 *                               |"media_group"|"sticker"|…
 *   msg_status       (String) — "sent"|"delivered"|"read"
 *   msg_timestamp    (long)   — epoch ms when the message was sent
 *   msg_delivered_at (long)   — epoch ms of delivery  (0 = unknown)
 *   msg_read_at      (long)   — epoch ms of read      (0 = unknown)
 *   msg_media_url    (String) — URL for image/video/sticker thumbnail
 *   msg_thumbnail_url(String) — thumbnail URL for video (fallback to mediaUrl)
 *   msg_media_count  (int)    — number of items in a media_group (0 = single)
 *   msg_forwarded_from(String)— non-empty when the message was forwarded
 */
public class MessageInfoActivity extends AppCompatActivity {

    // ── Intent extra keys ────────────────────────────────────────────────
    public static final String EXTRA_TEXT           = "msg_text";
    public static final String EXTRA_TYPE           = "msg_type";
    public static final String EXTRA_STATUS         = "msg_status";
    public static final String EXTRA_TIMESTAMP      = "msg_timestamp";
    public static final String EXTRA_DELIVERED_AT   = "msg_delivered_at";
    public static final String EXTRA_READ_AT        = "msg_read_at";
    public static final String EXTRA_MEDIA_URL      = "msg_media_url";
    public static final String EXTRA_THUMBNAIL_URL  = "msg_thumbnail_url";
    public static final String EXTRA_MEDIA_COUNT    = "msg_media_count";
    public static final String EXTRA_FORWARDED_FROM = "msg_forwarded_from";

    // ── Thread-safe date formatter ───────────────────────────────────────
    // SimpleDateFormat is NOT thread-safe; a single static instance can
    // produce corrupted output when accessed from multiple threads.  Using
    // ThreadLocal gives each thread its own instance at effectively zero
    // marginal cost (one lazy allocation per thread, cached forever after).
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() ->
                    new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()));

    // ── Pre-built Glide RequestOptions (avoids per-call allocation) ──────
    // PREFER_RGB_565: halves the per-pixel memory vs ARGB_8888 for a
    // small thumbnail preview where colour accuracy is unimportant.
    // DiskCacheStrategy.ALL: re-uses any previously cached entry that
    // ChatActivity's Glide warm-up already placed on disk.
    private static final RequestOptions MEDIA_OPTIONS = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
            .placeholder(android.R.color.darker_gray)
            .error(android.R.color.darker_gray);

    // ── Views — preview area ─────────────────────────────────────────────
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

    // ── Views — status rows ──────────────────────────────────────────────
    private LinearLayout llRowSeen;
    private TextView     tvSeenTime;
    private View         dividerSeenDelivered;
    private LinearLayout llRowDelivered;
    private TextView     tvDeliveredTime;
    private View         dividerDeliveredSent;
    private TextView     tvSentTime;

    // ────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Window optimisation: kill the default window background overdraw.
        // activity_message_info.xml's root already has android:background set,
        // so the theme window background is a full-screen overdraw that's
        // immediately covered — removing it saves one full-frame paint per
        // layout pass.
        getWindow().setBackgroundDrawable(null);

        setContentView(R.layout.activity_message_info);

        // ── Toolbar ───────────────────────────────────────────────────
        Toolbar toolbar = findViewById(R.id.toolbar_msg_info);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finishWithAnimation());

        // ── Bind views (all in one block — cheaper than scattered calls) ──
        llTextBubble         = findViewById(R.id.ll_text_bubble);
        tvForwardedLabel     = findViewById(R.id.tv_forwarded_label);
        llFileRow            = findViewById(R.id.ll_file_row);
        tvFileIcon           = findViewById(R.id.tv_file_icon);
        tvFileLabel          = findViewById(R.id.tv_file_label);
        tvMsgText            = findViewById(R.id.tv_msg_text);
        flMediaPreview       = findViewById(R.id.fl_media_preview);
        ivMsgMedia           = findViewById(R.id.iv_msg_media);
        ivPlayOverlay        = findViewById(R.id.iv_play_overlay);
        tvMediaCountBadge    = findViewById(R.id.tv_media_count_badge);
        tvMediaForwarded     = findViewById(R.id.tv_media_forwarded);
        llRowSeen            = findViewById(R.id.ll_row_seen);
        tvSeenTime           = findViewById(R.id.tv_seen_time);
        dividerSeenDelivered = findViewById(R.id.divider_seen_delivered);
        llRowDelivered       = findViewById(R.id.ll_row_delivered);
        tvDeliveredTime      = findViewById(R.id.tv_delivered_time);
        dividerDeliveredSent = findViewById(R.id.divider_delivered_sent);
        tvSentTime           = findViewById(R.id.tv_sent_time);

        // ── Read intent extras ────────────────────────────────────────
        Intent intent        = getIntent();
        String type          = intent.getStringExtra(EXTRA_TYPE);
        String status        = intent.getStringExtra(EXTRA_STATUS);
        String text          = intent.getStringExtra(EXTRA_TEXT);
        String mediaUrl      = intent.getStringExtra(EXTRA_MEDIA_URL);
        String thumbnailUrl  = intent.getStringExtra(EXTRA_THUMBNAIL_URL);
        String forwardedFrom = intent.getStringExtra(EXTRA_FORWARDED_FROM);
        long   timestamp     = intent.getLongExtra(EXTRA_TIMESTAMP,    0L);
        long   deliveredAt   = intent.getLongExtra(EXTRA_DELIVERED_AT, 0L);
        long   readAt        = intent.getLongExtra(EXTRA_READ_AT,      0L);
        int    mediaCount    = intent.getIntExtra (EXTRA_MEDIA_COUNT,  0);

        // Safe nullability
        if (type          == null) type          = "text";
        if (status        == null) status        = "sent";
        if (text          == null) text          = "";
        if (mediaUrl      == null) mediaUrl      = "";
        if (thumbnailUrl  == null) thumbnailUrl  = "";
        if (forwardedFrom == null) forwardedFrom = "";

        // ── Populate views ────────────────────────────────────────────
        populatePreview(type, text, mediaUrl, thumbnailUrl, forwardedFrom, mediaCount);
        populateStatusRows(status, timestamp, deliveredAt, readAt);
    }

    // Handle hardware back button — same slide-down animation as nav-up.
    @Override
    public void onBackPressed() {
        finishWithAnimation();
    }

    /**
     * Finish this activity with the matching slide-down animation so that
     * ChatActivity's window does NOT animate — only this screen slides away.
     *
     * overridePendingTransition(incoming, outgoing):
     *   incoming = 0      → ChatActivity snaps back instantly (no slide-in)
     *   outgoing = slide_out_bottom → we slide down and disappear
     *
     * This is the mirror of ChatActivity's launch call:
     *   overridePendingTransition(msg_info_slide_in_bottom, 0)
     */
    private void finishWithAnimation() {
        finish();
        overridePendingTransition(0, R.anim.msg_info_slide_out_bottom);
    }

    // ────────────────────────────────────────────────────────────────────
    // PREVIEW
    // ────────────────────────────────────────────────────────────────────

    private void populatePreview(String type, String text,
                                  String mediaUrl, String thumbnailUrl,
                                  String forwardedFrom, int mediaCount) {
        if (isMediaType(type) && !TextUtils.isEmpty(mediaUrl)) {
            showMediaPreview(type, mediaUrl, thumbnailUrl, forwardedFrom, mediaCount);
        } else {
            showTextBubble(type, text, forwardedFrom);
        }
    }

    private static boolean isMediaType(String type) {
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

        // Choose the best URL to display (video → thumbnail first)
        String loadUrl = "video".equals(type) && !TextUtils.isEmpty(thumbnailUrl)
                ? thumbnailUrl : mediaUrl;

        // Glide load — uses pre-built RequestOptions (no per-call allocation)
        Glide.with(this)
                .load(loadUrl)
                .apply(MEDIA_OPTIONS)
                .into(ivMsgMedia);

        // Video play-icon overlay
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
                tvFileIcon.setText("\uD83C\uDFB5"); // 🎵
                tvFileLabel.setVisibility(View.GONE);
                break;
            case "file":
                llFileRow.setVisibility(View.VISIBLE);
                tvFileIcon.setText("\uD83D\uDCCE"); // 📎
                if (!TextUtils.isEmpty(text)) {
                    tvFileLabel.setVisibility(View.VISIBLE);
                    tvFileLabel.setText(text);
                    text = ""; // don't show the same text again below
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
            tvMsgText.setVisibility(View.VISIBLE);
            tvMsgText.setText("(no content)");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // STATUS ROWS
    // ────────────────────────────────────────────────────────────────────

    private void populateStatusRows(String status, long timestamp,
                                     long deliveredAt, long readAt) {
        final boolean isDelivered = "delivered".equals(status) || "read".equals(status);
        final boolean isRead      = "read".equals(status);

        // Sent — always visible
        tvSentTime.setText(formatTs(timestamp));

        // Delivered row
        if (isDelivered) {
            llRowDelivered.setVisibility(View.VISIBLE);
            dividerDeliveredSent.setVisibility(View.VISIBLE);
            tvDeliveredTime.setText(formatTs(deliveredAt > 0 ? deliveredAt : timestamp));
        }

        // Seen row
        if (isRead) {
            llRowSeen.setVisibility(View.VISIBLE);
            dividerSeenDelivered.setVisibility(View.VISIBLE);
            tvSeenTime.setText(formatTs(readAt > 0 ? readAt : timestamp));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────────────

    /**
     * Format an epoch-ms timestamp for display.
     * Uses a ThreadLocal SimpleDateFormat — safe for use from any thread.
     */
    private static String formatTs(long epochMs) {
        if (epochMs <= 0) return "\u2013"; // en-dash
        SimpleDateFormat fmt = DATE_FMT.get();
        if (fmt == null) {
            fmt = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        }
        return fmt.format(new Date(epochMs));
    }
}
