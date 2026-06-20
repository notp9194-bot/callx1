package com.callx.app.chat.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * PollCreateBottomSheet — Feature: Poll / Voting (create step).
 *
 * Reusable across 1-on-1 chat and group chat. Shows a question field plus
 * a dynamic list of option fields (min 2, max 10, with add/remove).
 * On "Create poll" tap, validates input and hands back
 * (question, non-blank options) via the Listener.
 */
public class PollCreateBottomSheet {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;

    public interface Listener {
        void onPollCreate(String question, List<String> options);
    }

    /** Inflate and show the create-poll sheet on top of {@code activity}. */
    public static void show(Activity activity, Listener listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View v = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_create_poll, null);

        EditText etQuestion = v.findViewById(R.id.et_poll_question);
        LinearLayout optionsContainer = v.findViewById(R.id.ll_poll_options_container);
        TextView btnAddOption = v.findViewById(R.id.btn_add_poll_option);
        TextView btnCancel = v.findViewById(R.id.btn_cancel_poll);
        TextView btnCreate = v.findViewById(R.id.btn_create_poll);

        List<EditText> optionFields = new ArrayList<>();

        Runnable[] addOptionRowHolder = new Runnable[1];
        addOptionRowHolder[0] = () -> {
            if (optionFields.size() >= MAX_OPTIONS) {
                Toast.makeText(activity, "Max " + MAX_OPTIONS + " options allowed", Toast.LENGTH_SHORT).show();
                return;
            }
            View row = LayoutInflater.from(activity)
                    .inflate(R.layout.item_poll_option_input, optionsContainer, false);
            EditText etOption = row.findViewById(R.id.et_poll_option);
            View btnRemove = row.findViewById(R.id.btn_remove_poll_option);
            optionFields.add(etOption);
            optionsContainer.addView(row);

            btnRemove.setOnClickListener(x -> {
                if (optionFields.size() <= MIN_OPTIONS) {
                    Toast.makeText(activity, "At least " + MIN_OPTIONS + " options needed", Toast.LENGTH_SHORT).show();
                    return;
                }
                optionFields.remove(etOption);
                optionsContainer.removeView(row);
                updateRemoveVisibility(optionFields);
            });

            updateRemoveVisibility(optionFields);
        };

        // Start with 2 empty options
        addOptionRowHolder[0].run();
        addOptionRowHolder[0].run();

        btnAddOption.setOnClickListener(x -> addOptionRowHolder[0].run());
        btnCancel.setOnClickListener(x -> sheet.dismiss());

        btnCreate.setOnClickListener(x -> {
            String question = etQuestion.getText().toString().trim();
            if (question.isEmpty()) {
                etQuestion.setError("Question is required");
                return;
            }
            List<String> options = new ArrayList<>();
            for (EditText et : optionFields) {
                String opt = et.getText().toString().trim();
                if (!opt.isEmpty()) options.add(opt);
            }
            if (options.size() < MIN_OPTIONS) {
                Toast.makeText(activity, "Add at least " + MIN_OPTIONS + " options", Toast.LENGTH_SHORT).show();
                return;
            }
            sheet.dismiss();
            listener.onPollCreate(question, options);
        });

        sheet.setContentView(v);
        sheet.show();
    }

    private static void updateRemoveVisibility(List<EditText> optionFields) {
        boolean canRemove = optionFields.size() > MIN_OPTIONS;
        for (EditText et : optionFields) {
            View parent = (View) et.getParent();
            View btnRemove = parent.findViewById(R.id.btn_remove_poll_option);
            if (btnRemove != null) btnRemove.setVisibility(canRemove ? View.VISIBLE : View.GONE);
        }
    }
}
