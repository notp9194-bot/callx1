package com.callx.app.bottomsheet;

import android.content.Context;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.*;

/**
 * StatusPollBottomSheet v26 — Create poll/question/quiz overlay for status.
 * Types: Poll (multiple choice), Question (open text), Quiz (1 correct answer).
 */
public class StatusPollBottomSheet {
    public enum PollType { POLL, QUESTION, QUIZ }
    public static class PollData {
        public PollType type; public String question;
        public List<String> options = new ArrayList<>();
        public int correctIndex = -1; // Quiz only
    }
    public interface OnPollCreated { void onCreated(PollData data); }

    public static void show(Context ctx, OnPollCreated cb) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx,20), dp(ctx,12), dp(ctx,20), dp(ctx,32));

        TextView title = tv(ctx,"📊 Add Poll / Question", 17, true); root.addView(title);

        // Type selector
        RadioGroup rg = new RadioGroup(ctx); rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbPoll = new RadioButton(ctx); rbPoll.setText("Poll"); rbPoll.setId(1);
        RadioButton rbQ    = new RadioButton(ctx); rbQ.setText("Question"); rbQ.setId(2);
        RadioButton rbQuiz = new RadioButton(ctx); rbQuiz.setText("Quiz"); rbQuiz.setId(3);
        rg.addView(rbPoll); rg.addView(rbQ); rg.addView(rbQuiz); rg.check(1);
        root.addView(rg);

        EditText etQuestion = new EditText(ctx); etQuestion.setHint("Ask a question…"); etQuestion.setMaxLines(3);
        root.addView(etQuestion);

        // Options container (for Poll/Quiz)
        LinearLayout optionsContainer = new LinearLayout(ctx); optionsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(optionsContainer);

        EditText opt1 = addOptionField(ctx, optionsContainer, "Option 1");
        EditText opt2 = addOptionField(ctx, optionsContainer, "Option 2");
        Button btnAddOpt = new Button(ctx); btnAddOpt.setText("+ Add option");
        final List<EditText> optFields = new ArrayList<>(Arrays.asList(opt1, opt2));

        btnAddOpt.setOnClickListener(v -> {
            if (optFields.size() >= 4) { Toast.makeText(ctx,"Max 4 options",Toast.LENGTH_SHORT).show(); return; }
            EditText et = addOptionField(ctx, optionsContainer, "Option " + (optFields.size()+1));
            optFields.add(et);
        });
        root.addView(btnAddOpt);

        rg.setOnCheckedChangeListener((g, id) -> {
            boolean showOpts = (id == 1 || id == 3);
            optionsContainer.setVisibility(showOpts ? View.VISIBLE : View.GONE);
            btnAddOpt.setVisibility(showOpts ? View.VISIBLE : View.GONE);
        });

        Button btnCreate = new Button(ctx); btnCreate.setText("Add to Status"); root.addView(btnCreate);
        btnCreate.setOnClickListener(v -> {
            String q = etQuestion.getText().toString().trim();
            if (q.isEmpty()) { etQuestion.setError("Enter a question"); return; }
            PollData data = new PollData();
            data.question = q;
            int checkedId = rg.getCheckedRadioButtonId();
            data.type = checkedId == 2 ? PollType.QUESTION : checkedId == 3 ? PollType.QUIZ : PollType.POLL;
            if (data.type != PollType.QUESTION) {
                for (EditText ef : optFields) {
                    String opt = ef.getText().toString().trim();
                    if (!opt.isEmpty()) data.options.add(opt);
                }
                if (data.options.size() < 2) { Toast.makeText(ctx,"Need at least 2 options",Toast.LENGTH_SHORT).show(); return; }
            }
            if (cb != null) cb.onCreated(data);
            sheet.dismiss();
        });

        ScrollView sv = new ScrollView(ctx); sv.addView(root);
        sheet.setContentView(sv); sheet.show();
    }

    private static EditText addOptionField(Context ctx, LinearLayout container, String hint) {
        EditText et = new EditText(ctx); et.setHint(hint); et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx,8), 0, 0); et.setLayoutParams(lp);
        container.addView(et); return et;
    }
    private static TextView tv(Context ctx, String t, int sz, boolean bold) {
        TextView v = new TextView(ctx); v.setText(t); v.setTextSize(sz);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD); return v;
    }
    private static int dp(Context ctx, int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }
}
