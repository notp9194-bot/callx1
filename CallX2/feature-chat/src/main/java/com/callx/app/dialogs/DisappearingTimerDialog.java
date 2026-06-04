package com.callx.app.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.callx.app.chat.R;
import com.callx.app.managers.DisappearingMessagesManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * DisappearingTimerDialog — Bottom sheet to pick disappearing message timer.
 *
 * Shows radio options: Off / 5s / 30s / 1 min / 1 hr / 24 hr / 7 days
 * Currently selected option is pre-checked.
 * On "Set" tap → calls listener with chosen duration.
 *
 * Usage in ChatActivity:
 *   DisappearingTimerDialog.show(getSupportFragmentManager(), currentDuration,
 *       duration -> {
 *           disappearingManager.setTimer(duration);
 *           updateDisappearHeader(duration);
 *       });
 */
public class DisappearingTimerDialog extends BottomSheetDialogFragment {

    public interface OnTimerSelected { void onSelected(long durationMs); }

    private static final String ARG_CURRENT = "currentDuration";

    private long             currentDuration;
    private OnTimerSelected  listener;

    public static DisappearingTimerDialog newInstance(long currentDuration) {
        DisappearingTimerDialog d = new DisappearingTimerDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_CURRENT, currentDuration);
        d.setArguments(args);
        return d;
    }

    public static void show(androidx.fragment.app.FragmentManager fm,
                            long currentDuration, OnTimerSelected listener) {
        DisappearingTimerDialog dialog = newInstance(currentDuration);
        dialog.listener = listener;
        dialog.show(fm, "DisappearingTimerDialog");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            currentDuration = getArguments().getLong(ARG_CURRENT, 0L);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialog_disappearing_timer, container, false);
        bindViews(v);
        return v;
    }

    private void bindViews(View v) {
        TextView tvTitle    = v.findViewById(R.id.tv_dialog_title);
        RadioGroup rgTimer  = v.findViewById(R.id.rg_timer);
        TextView btnSet     = v.findViewById(R.id.btn_set_timer);
        TextView btnCancel  = v.findViewById(R.id.btn_cancel_timer);

        tvTitle.setText("Disappearing Messages");

        // Map of duration → RadioButton ID
        long[]  durations = {
                DisappearingMessagesManager.DURATION_OFF,
                DisappearingMessagesManager.DURATION_5S,
                DisappearingMessagesManager.DURATION_30S,
                DisappearingMessagesManager.DURATION_1MIN,
                DisappearingMessagesManager.DURATION_1HR,
                DisappearingMessagesManager.DURATION_24HR,
                DisappearingMessagesManager.DURATION_7DAYS
        };
        int[] radioIds = {
                R.id.rb_off, R.id.rb_5s, R.id.rb_30s,
                R.id.rb_1min, R.id.rb_1hr, R.id.rb_24hr, R.id.rb_7days
        };

        // Pre-check current selection
        for (int i = 0; i < durations.length; i++) {
            if (durations[i] == currentDuration) {
                RadioButton rb = v.findViewById(radioIds[i]);
                if (rb != null) rb.setChecked(true);
                break;
            }
        }

        // Dismiss on cancel
        btnCancel.setOnClickListener(x -> dismiss());

        // "Set" button — read selected radio, fire callback
        btnSet.setOnClickListener(x -> {
            int selectedId  = rgTimer.getCheckedRadioButtonId();
            long chosen     = DisappearingMessagesManager.DURATION_OFF;

            for (int i = 0; i < radioIds.length; i++) {
                if (radioIds[i] == selectedId) {
                    chosen = durations[i];
                    break;
                }
            }

            if (listener != null) listener.onSelected(chosen);
            dismiss();
        });
    }
}
