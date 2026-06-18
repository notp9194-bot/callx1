package com.callx.app.interactions;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;
import com.callx.app.activities.StatusViewerActivity;
/**
 * StatusSeenByBottomSheet v26 — Full seen-by list with avatars + timestamps.
 * FIX v26: show() now accepts optional Runnable onDismiss — called via setOnDismissListener,
 *           replacing the brittle 300 ms Handler delay in StatusViewerActivity.
 * FIX v26: Avatar loading now tries thumbUrl first, falls back to photoUrl (consistent
 *           with SeenBy's own Firebase read logic).
 */
public class StatusSeenByBottomSheet {
    /** Legacy overload — no dismiss callback (used when caller doesn't need resume). */
    public static void show(Context ctx, StatusItem item) {
        show(ctx, item, null);
    }
    public static void show(Context ctx, StatusItem item, Runnable onDismiss) {
        if (item.seenBy == null || item.seenBy.isEmpty()) {
            Toast.makeText(ctx, "No viewers yet", Toast.LENGTH_SHORT).show();
            if (onDismiss != null) onDismiss.run();
            return;
        }
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root  = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx,16), dp(ctx,8), dp(ctx,16), dp(ctx,24));
        // Header
        TextView header = new TextView(ctx);
        header.setText("Seen by " + item.seenBy.size());
        header.setTextSize(17);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(ctx,8), 0, dp(ctx,8));
        root.addView(header);
        // Reaction summary strip
        if (item.reactions != null && !item.reactions.isEmpty()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String e : item.reactions.values()) counts.merge(e, 1, Integer::sum);
            LinearLayout reactionStrip = new LinearLayout(ctx);
            reactionStrip.setOrientation(LinearLayout.HORIZONTAL);
            reactionStrip.setPadding(0, 0, 0, dp(ctx,12));
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                TextView chip = new TextView(ctx);
                chip.setText(e.getKey() + " " + e.getValue());
                chip.setTextSize(14);
                chip.setPadding(dp(ctx,10), dp(ctx,4), dp(ctx,10), dp(ctx,4));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(ctx,16));
                bg.setColor(Color.parseColor("#F5F5F5"));
                chip.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(dp(ctx,8));
                chip.setLayoutParams(lp);
                reactionStrip.addView(chip);
            }
            root.addView(reactionStrip);
        }
        // Progress indicator
        ProgressBar progress = new ProgressBar(ctx);
        progress.setVisibility(View.VISIBLE);
        root.addView(progress);
        scroll.addView(root);
        sheet.setContentView(scroll);
        // FIX v26: proper dismiss listener instead of Handler delay
        if (onDismiss != null) {
            sheet.setOnDismissListener(d -> onDismiss.run());
        }
        sheet.show();
        // Resolve UIDs → user profiles from Firebase
        List<String> uids = new ArrayList<>(item.seenBy.keySet());
        SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        int[] loaded = {0};
        for (String uid : uids) {
            final String fUid = uid;
            final long seenAt = item.seenBy.get(uid) != null ? item.seenBy.get(uid) : 0;
            final String reaction = (item.reactions != null ? item.reactions.get(fUid) : null);
            FirebaseUtils.db().getReference("users").child(uid).get()
                .addOnSuccessListener(snap -> {
                    String name  = snap.child("name").getValue(String.class);
                    // FIX v26: thumbUrl first, fallback photoUrl
                    String photo = snap.child("thumbUrl").getValue(String.class);
                    if (photo == null) photo = snap.child("photoUrl").getValue(String.class);
                    final String fname  = name  != null ? name  : fUid;
                    final String fphoto = photo;
                    loaded[0]++;
                    if (loaded[0] == uids.size()) progress.setVisibility(View.GONE);
                    addViewerRow(ctx, root, fUid, fname, fphoto,
                            seenAt > 0 ? fmt.format(new Date(seenAt)) : "",
                            reaction);
                });
        }
    }
    private static void addViewerRow(Context ctx, LinearLayout parent, String uid,
                                      String name, String photoUrl,
                                      String time, String reaction) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx,10), 0, dp(ctx,10));
        // Avatar
        de.hdodenhof.circleimageview.CircleImageView avatar =
            new de.hdodenhof.circleimageview.CircleImageView(ctx);
        int size = dp(ctx,44);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(ctx).load(photoUrl).into(avatar);
        } else {
            avatar.setImageResource(android.R.drawable.ic_menu_my_calendar);
        }
        row.addView(avatar);
        // Name + time
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(ctx,12), 0, 0, 0);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(infoLp);
        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextSize(15);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(tvName);
        if (!time.isEmpty()) {
            TextView tvTime = new TextView(ctx);
            tvTime.setText("Seen " + time);
            tvTime.setTextSize(12);
            tvTime.setTextColor(Color.GRAY);
            info.addView(tvTime);
        }
        row.addView(info);
        // Reaction emoji (if reacted)
        if (reaction != null) {
            TextView tvReact = new TextView(ctx);
            tvReact.setText(reaction);
            tvReact.setTextSize(22);
            tvReact.setPadding(dp(ctx,8), 0, 0, 0);
            row.addView(tvReact);
        }
        parent.addView(row);
        // Divider
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#11000000"));
        parent.addView(divider);
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}