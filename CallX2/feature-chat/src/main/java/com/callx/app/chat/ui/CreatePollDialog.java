package com.callx.app.chat.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Poll feature — bottom sheet for composing a new poll.
 *
 * Lets the user type a question, add/remove answer options (min 2, max 10),
 * optionally mark the poll as anonymous, optionally allow voters to tick
 * more than one option ("advanced"/multi-choice polls), and submit. The
 * resulting question + option list is handed back via
 * {@link OnPollCreatedListener} so the caller (ChatActivity / GroupChatActivity)
 * can build and send the Message.
 *
 * Validation (inline errors on the offending field, never silent):
 *  - question required, max {@link #MAX_QUESTION_LEN} chars; options max {@link #MAX_OPTION_LEN}
 *  - options are whitespace-normalised; duplicates (case-insensitive) are rejected, not dropped
 *  - rows left empty are ignored, but at least {@link #MIN_OPTIONS} real options are required
 */
public class CreatePollDialog {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;
    private static final int MAX_QUESTION_LEN = 200;
    private static final int MAX_OPTION_LEN = 100;

    public interface OnPollCreatedListener {
        /**
         * @param multiChoice true if voters may tick more than one option
         *                     (checkbox-style poll); false = single choice (radio).
         */
        void onPollCreated(String question, List<String> options, boolean anonymous, boolean multiChoice);
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
        SwitchCompat switchMultiChoice = sheet.findViewById(R.id.switch_poll_multi_choice);
        SwitchCompat switchAnonymous = sheet.findViewById(R.id.switch_poll_anonymous);
        View btnCancel = sheet.findViewById(R.id.btn_cancel_poll);
        View btnSend = sheet.findViewById(R.id.btn_send_poll);

        etQuestion.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_QUESTION_LEN)});
        etQuestion.addTextChangedListener(new ClearErrorWatcher(etQuestion));

        // Start with 2 empty option rows (minimum required for a poll)
        addOptionRow(activity, optionsContainer, false);
        addOptionRow(activity, optionsContainer, false);

        btnAddOption.setOnClickListener(v -> {
            if (optionsContainer.getChildCount() >= MAX_OPTIONS) {
                Toast.makeText(activity, activity.getString(R.string.poll_max_options, MAX_OPTIONS),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            addOptionRow(activity, optionsContainer, true); // new row takes focus, ready to type
        });

        btnCancel.setOnClickListener(v -> dlg.dismiss());

        btnSend.setOnClickListener(v -> {
            String question = etQuestion.getText() != null ? etQuestion.getText().toString().trim() : "";
            if (question.isEmpty()) {
                etQuestion.setError(activity.getString(R.string.poll_err_question));
                etQuestion.requestFocus();
                return;
            }

            List<String> options = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            EditText firstEmpty = null;
            EditText firstDuplicate = null;
            for (int i = 0; i < optionsContainer.getChildCount(); i++) {
                EditText etOpt = optionsContainer.getChildAt(i).findViewById(R.id.et_poll_option);
                String text = normalize(etOpt.getText());
                if (text.isEmpty()) {
                    if (firstEmpty == null) firstEmpty = etOpt;
                    continue;
                }
                if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                    if (firstDuplicate == null) firstDuplicate = etOpt;
                    continue;
                }
                options.add(text);
            }

            if (firstDuplicate != null) {
                firstDuplicate.setError(activity.getString(R.string.poll_err_duplicate));
                firstDuplicate.requestFocus();
                return;
            }
            if (options.size() < MIN_OPTIONS) {
                EditText target = firstEmpty != null ? firstEmpty
                        : (EditText) optionsContainer.getChildAt(0).findViewById(R.id.et_poll_option);
                target.setError(activity.getString(R.string.poll_err_min_options, MIN_OPTIONS));
                target.requestFocus();
                return;
            }

            boolean multiChoice = switchMultiChoice != null && switchMultiChoice.isChecked();
            boolean anonymous = switchAnonymous.isChecked();
            dlg.dismiss();
            if (listener != null) listener.onPollCreated(question, options, anonymous, multiChoice);
        });

        dlg.show();
    }

    private static void addOptionRow(Activity activity, LinearLayout container, boolean focus) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_poll_option_input, container, false);
        EditText etOpt = row.findViewById(R.id.et_poll_option);
        etOpt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_OPTION_LEN)});
        etOpt.addTextChangedListener(new ClearErrorWatcher(etOpt));
        View btnRemove = row.findViewById(R.id.btn_remove_poll_option);
        btnRemove.setOnClickListener(v -> {
            if (container.getChildCount() > MIN_OPTIONS) {
                container.removeView(row);
                renumberHints(activity, container);
            } else {
                Toast.makeText(activity, activity.getString(R.string.poll_min_options_needed, MIN_OPTIONS),
                        Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(row);
        renumberHints(activity, container);
        if (focus) etOpt.requestFocus();
    }

    /** "Option 1", "Option 2", … — kept in step when rows are added/removed. */
    private static void renumberHints(Activity activity, LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            EditText et = container.getChildAt(i).findViewById(R.id.et_poll_option);
            if (et != null) et.setHint(activity.getString(R.string.poll_option_hint_n, i + 1));
        }
    }

    /** Trim + collapse inner whitespace so "Yes", " yes " and "Yes  " count as the same option. */
    private static String normalize(Editable e) {
        return e == null ? "" : e.toString().trim().replaceAll("\\s+", " ");
    }

    /** Clears a field's inline error as soon as the user edits it. */
    private static final class ClearErrorWatcher implements TextWatcher {
        private final EditText field;
        ClearErrorWatcher(EditText field) { this.field = field; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { if (field.getError() != null) field.setError(null); }
    }
}
