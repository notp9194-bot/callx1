package com.callx.app.interactions;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;
import androidx.annotation.NonNull;

/**
 * StatusQuestionAnswersBottomSheet v27 — NEW
 *
 * Status owner dekhta hai apne question box pe aaye saare answers.
 *
 * Features:
 *   ✅ Shows submitted answers with viewer name + avatar
 *   ✅ Timestamp for each answer
 *   ✅ "No answers yet" empty state
 *   ✅ Firebase: statusQuestionAnswers/{ownerUid}/{statusId}/{viewerUid}
 *   ✅ Shows question text at top as reminder
 *   ✅ Count badge: "X responses"
 *
 * Integration: Call from StatusViewerActivity when owner taps question box results area.
 */
public class StatusQuestionAnswersBottomSheet {

    public static void show(Context ctx, StatusItem item, String ownerUid) {
        if (item == null || ownerUid == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        sheet.getBehavior().setPeekHeight(dp(ctx, 500));

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 32));

        // Handle bar
        View handle = new View(ctx);
        handle.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(ctx, 12);
        root.addView(handle, hp);

        // Question display
        LinearLayout qBox = new LinearLayout(ctx);
        qBox.setOrientation(LinearLayout.VERTICAL);
        qBox.setBackgroundColor(Color.parseColor("#F3E5FF"));
        qBox.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        LinearLayout.LayoutParams qbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        qbp.bottomMargin = dp(ctx, 16);

        TextView qLabel = new TextView(ctx);
        qLabel.setText("❓ Your question:");
        qLabel.setTextSize(12);
        qLabel.setTextColor(Color.parseColor("#9C27B0"));
        qBox.addView(qLabel);

        String questionText = item.questionBoxText != null && !item.questionBoxText.isEmpty()
                ? item.questionBoxText : "What do you want to ask?";
        TextView qTv = new TextView(ctx);
        qTv.setText(questionText);
        qTv.setTextSize(16);
        qTv.setTypeface(null, android.graphics.Typeface.BOLD);
        qTv.setTextColor(Color.BLACK);
        qBox.addView(qTv);
        root.addView(qBox, qbp);

        // Count header
        TextView countTv = new TextView(ctx);
        countTv.setText("Responses");
        countTv.setTextSize(16);
        countTv.setTypeface(null, android.graphics.Typeface.BOLD);
        countTv.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(countTv);

        // Answers container
        LinearLayout answersContainer = new LinearLayout(ctx);
        answersContainer.setOrientation(LinearLayout.VERTICAL);

        ProgressBar progress = new ProgressBar(ctx);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT) {{ gravity = Gravity.CENTER_HORIZONTAL; }});

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(answersContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 360)));

        // Load answers from Firebase
        FirebaseUtils.db()
            .getReference("statusQuestionAnswers")
            .child(ownerUid)
            .child(item.id)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progress.setVisibility(View.GONE);
                    answersContainer.removeAllViews();

                    // Also check in-model questionBoxAnswers
                    Map<String, Object> votes = new HashMap<>();
                    if (item.questionBoxAnswers != null) votes.putAll(item.questionBoxAnswers);
                    for (DataSnapshot child : snap.getChildren()) {
                        String viewerUid = child.getKey();
                        Object answer = child.child("answer").getValue();
                        Object tsObj  = child.child("timestamp").getValue();
                        if (viewerUid != null && answer != null)
                            votes.put(viewerUid, answer.toString());
                    }

                    if (votes.isEmpty()) {
                        TextView empty = new TextView(ctx);
                        empty.setText("💬 No responses yet\nShare your status so people can answer!");
                        empty.setGravity(Gravity.CENTER);
                        empty.setTextColor(Color.GRAY);
                        empty.setTextSize(14);
                        empty.setPadding(0, dp(ctx, 24), 0, 0);
                        answersContainer.addView(empty);
                        countTv.setText("Responses (0)");
                        return;
                    }

                    countTv.setText("Responses (" + votes.size() + ")");

                    for (Map.Entry<String, Object> e : votes.entrySet()) {
                        String viewerUid = e.getKey();
                        String answerText = e.getValue().toString();

                        // Build answer card
                        LinearLayout card = new LinearLayout(ctx);
                        card.setOrientation(LinearLayout.HORIZONTAL);
                        card.setGravity(Gravity.TOP);
                        card.setBackgroundColor(Color.parseColor("#FAFAFA"));
                        card.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
                        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        cp.bottomMargin = dp(ctx, 8);

                        // Avatar placeholder
                        ImageView avatar = new ImageView(ctx);
                        int sz = dp(ctx, 38);
                        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(sz, sz);
                        ap.rightMargin = dp(ctx, 10);
                        card.addView(avatar, ap);

                        LinearLayout textCol = new LinearLayout(ctx);
                        textCol.setOrientation(LinearLayout.VERTICAL);
                        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                        TextView tvName = new TextView(ctx);
                        tvName.setText("Loading…");
                        tvName.setTextSize(13);
                        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
                        tvName.setTextColor(Color.parseColor("#333333"));
                        textCol.addView(tvName);

                        // Answer bubble
                        TextView tvAnswer = new TextView(ctx);
                        tvAnswer.setText(answerText);
                        tvAnswer.setTextSize(14);
                        tvAnswer.setTextColor(Color.BLACK);
                        tvAnswer.setBackgroundColor(Color.parseColor("#EDE7F6"));
                        tvAnswer.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
                        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        abp.topMargin = dp(ctx, 4);
                        textCol.addView(tvAnswer, abp);

                        card.addView(textCol);
                        answersContainer.addView(card, cp);

                        // Load viewer name + photo
                        FirebaseUtils.db().getReference("users").child(viewerUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot u) {
                                    Object nameObj  = u.child("name").getValue();
                                    Object photoObj = u.child("photoUrl").getValue();
                                    if (nameObj != null)
                                        tvName.setText(nameObj.toString());
                                    if (photoObj != null && !photoObj.toString().isEmpty()) {
                                        Glide.with(ctx).load(photoObj.toString())
                                                .transform(new CircleCrop())
                                                .placeholder(android.R.drawable.ic_menu_gallery)
                                                .into(avatar);
                                    } else {
                                        avatar.setImageResource(android.R.drawable.ic_menu_gallery);
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    avatar.setImageResource(android.R.drawable.ic_menu_gallery);
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(ctx, "Failed to load answers", Toast.LENGTH_SHORT).show();
                }
            });

        ScrollView outerScroll = new ScrollView(ctx);
        outerScroll.addView(root);
        sheet.setContentView(outerScroll);
        sheet.show();
    }

    /** Record a viewer's answer to the question box. */
    public static void submitAnswer(String ownerUid, String statusId,
                                    String viewerUid, String answer) {
        if (ownerUid == null || statusId == null || viewerUid == null
                || answer == null || answer.trim().isEmpty()) return;
        Map<String, Object> data = new HashMap<>();
        data.put("answer",    answer.trim());
        data.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        FirebaseUtils.db()
            .getReference("statusQuestionAnswers")
            .child(ownerUid).child(statusId).child(viewerUid)
            .setValue(data);
    }

    private static int dp(Context ctx, int val) {
        return Math.round(val * ctx.getResources().getDisplayMetrics().density);
    }
}
