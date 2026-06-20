package com.callx.app.chat.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Poll feature — bottom sheet for composing a new poll.
 *
 * Lets the user type a question, add/remove answer options (min 2, max 10),
 * optionally mark the poll as anonymous, and submit. The resulting
 * question + option list is handed back via {@link OnPollCreatedListener}
 * so the caller (ChatActivity) can build and send the Message.
 */
public class CreatePollDialog {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;

    public interface OnPollCreatedListener {
        void onPollCreated(String question, List<String> options, boolean anonymous);
    }

    private CreatePollDialog() {}

    public static void show(Activity activity, OnPollCreatedListener listener) {
        BottomSheetDialog dlg = new BottomSheetDialog(activity);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_create_poll, null);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);

        EditText etQuestion = sheet.findViewById(R.id.et_poll_question);
        LinearLayout optionsContainer = sheet.findViewById(R.id.ll_poll_options_container);
        View btnAddOption = sheet.findViewById(R.id.btn_add_poll_option);
        SwitchCompat switchAnonymous = sheet.findViewById(R.id.switch_poll_anonymous);
        View btnCancel = sheet.findViewById(R.id.btn_cancel_poll);
        View btnSend = sheet.findViewById(R.id.btn_send_poll);

        // Start with 2 empty option rows (minimum required for a poll)
        addOptionRow(activity, optionsContainer);
        addOptionRow(activity, optionsContainer);

        btnAddOption.setOnClickListener(v -> {
            if (optionsContainer.getChildCount() >= MAX_OPTIONS) {
                Toast.makeText(activity, "Max " + MAX_OPTIONS + " options allowed", Toast.LENGTH_SHORT).show();
                return;
            }
            addOptionRow(activity, optionsContainer);
        });

        btnCancel.setOnClickListener(v -> dlg.dismiss());

        btnSend.setOnClickListener(v -> {
            String question = etQuestion.getText() != null ? etQuestion.getText().toString().trim() : "";
            if (question.isEmpty()) {
                etQuestion.setError("Question required");
                return;
            }

            List<String> options = new ArrayList<>();
            for (int i = 0; i < optionsContainer.getChildCount(); i++) {
                View row = optionsContainer.getChildAt(i);
                EditText etOpt = row.findViewById(R.id.et_poll_option);
                String text = etOpt.getText() != null ? etOpt.getText().toString().trim() : "";
                if (!text.isEmpty()) options.add(text);
            }

            if (options.size() < MIN_OPTIONS) {
                Toast.makeText(activity, "Add at least " + MIN_OPTIONS + " options", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean anonymous = switchAnonymous.isChecked();
            dlg.dismiss();
            if (listener != null) listener.onPollCreated(question, options, anonymous);
        });

        dlg.show();
    }

    private static void addOptionRow(Activity activity, LinearLayout container) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_poll_option_input, container, false);
        View btnRemove = row.findViewById(R.id.btn_remove_poll_option);
        btnRemove.setOnClickListener(v -> {
            if (container.getChildCount() > MIN_OPTIONS) {
                container.removeView(row);
            } else {
                Toast.makeText(activity, "At least " + MIN_OPTIONS + " options needed", Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(row);
    }
}
