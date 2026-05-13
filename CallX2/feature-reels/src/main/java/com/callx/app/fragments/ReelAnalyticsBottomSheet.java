package com.callx.app.fragments;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.*;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.*;
import java.util.Date;
import java.util.Locale;

/**
 * Feature 15: ReelAnalyticsBottomSheet
 *
 * Shown when the reel owner long-presses their own reel thumbnail.
 * Displays: Views, Likes, Comments, Saves, Shares + Engagement Rate.
 *
 * Firebase paths read:
 *  - reels/{reelId}/  → likesCount, commentsCount, sharesCount, viewsCount
 *  - reelSavesIndex/{reelId}/ → count of saves
 *  - reelReposts/{reelId}/    → count of reposts
 */
public class ReelAnalyticsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_REEL_ID   = "reel_id";
    private static final String ARG_THUMB_URL = "thumb_url";
    private static final String ARG_CAPTION   = "caption";
    private static final String ARG_TIMESTAMP = "timestamp";

    public static ReelAnalyticsBottomSheet newInstance(ReelModel reel) {
        ReelAnalyticsBottomSheet f = new ReelAnalyticsBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_REEL_ID,   reel.reelId);
        b.putString(ARG_THUMB_URL, reel.thumbUrl);
        b.putString(ARG_CAPTION,   reel.caption);
        b.putLong(ARG_TIMESTAMP,   reel.timestamp);
        f.setArguments(b);
        return f;
    }

    private String reelId, thumbUrl, caption;
    private long   timestamp;

    private ImageView  ivThumb;
    private TextView   tvCaption, tvDate;
    private TextView   tvViews, tvLikes, tvComments, tvSaves, tvShares, tvEngagementRate;
    private ImageButton btnClose;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottom_sheet_reel_analytics, container, false);

        if (getArguments() != null) {
            reelId    = getArguments().getString(ARG_REEL_ID);
            thumbUrl  = getArguments().getString(ARG_THUMB_URL);
            caption   = getArguments().getString(ARG_CAPTION);
            timestamp = getArguments().getLong(ARG_TIMESTAMP);
        }

        bindViews(v);
        populateHeader();
        loadStats();
        return v;
    }

    private void bindViews(View v) {
        ivThumb          = v.findViewById(R.id.iv_analytics_thumb);
        tvCaption        = v.findViewById(R.id.tv_analytics_caption);
        tvDate           = v.findViewById(R.id.tv_analytics_date);
        tvViews          = v.findViewById(R.id.tv_stat_views);
        tvLikes          = v.findViewById(R.id.tv_stat_likes);
        tvComments       = v.findViewById(R.id.tv_stat_comments);
        tvSaves          = v.findViewById(R.id.tv_stat_saves);
        tvShares         = v.findViewById(R.id.tv_stat_shares);
        tvEngagementRate = v.findViewById(R.id.tv_engagement_rate);
        btnClose         = v.findViewById(R.id.btn_close_analytics);
        if (btnClose != null) btnClose.setOnClickListener(vv -> dismiss());
    }

    private void populateHeader() {
        if (thumbUrl != null && !thumbUrl.isEmpty() && isAdded() && getContext() != null)
            Glide.with(requireContext()).load(thumbUrl).centerCrop().into(ivThumb);

        if (tvCaption != null)
            tvCaption.setText(caption != null && !caption.isEmpty() ? caption : "No caption");

        if (tvDate != null && timestamp > 0) {
            String dateStr = DateFormat.format("MMM dd, yyyy", new Date(timestamp)).toString();
            tvDate.setText("Posted " + dateStr);
        }
    }

    private void loadStats() {
        if (reelId == null) return;

        // Load main reel stats
        FirebaseUtils.getReelsRef().child(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    long views    = getLong(snap, "viewsCount");
                    long likes    = getLong(snap, "likesCount");
                    long comments = getLong(snap, "commentsCount");
                    long shares   = getLong(snap, "sharesCount");

                    if (tvViews    != null) tvViews.setText(formatCount(views));
                    if (tvLikes    != null) tvLikes.setText(formatCount(likes));
                    if (tvComments != null) tvComments.setText(formatCount(comments));
                    if (tvShares   != null) tvShares.setText(formatCount(shares));

                    // Load saves count from index
                    FirebaseUtils.getReelSavesIndexRef(reelId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot savesSnap) {
                                if (!isAdded() || getContext() == null) return;
                                long saves = savesSnap.getChildrenCount();
                                if (tvSaves != null) tvSaves.setText(formatCount(saves));
                                // Engagement rate = (likes + comments + shares + saves) / views * 100
                                double eng = views > 0
                                    ? (likes + comments + shares + saves) * 100.0 / views : 0.0;
                                if (tvEngagementRate != null)
                                    tvEngagementRate.setText(String.format(Locale.getDefault(), "%.1f%%", eng));
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private long getLong(DataSnapshot snap, String key) {
        Long v = snap.child(key).getValue(Long.class);
        return v != null ? v : 0L;
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.getDefault(), "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
