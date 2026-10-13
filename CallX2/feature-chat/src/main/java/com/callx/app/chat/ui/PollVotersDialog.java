package com.callx.app.chat.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.callx.app.chat.R;
import com.callx.app.models.Message;
import com.callx.app.utils.AlertDialogStyler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** "Poll votes" list: each option with the people who picked it. Shared by 1:1 and group chats. */
public final class PollVotersDialog {

    public interface NameResolver { String nameOf(String uid); }
    /** Optional custom row (e.g. avatar row in groups). Return null for a plain text row. */
    public interface RowFactory { View create(String uid, String name); }

    private PollVotersDialog() {}

    public static void show(Activity a, Message m, NameResolver names, RowFactory rows) {
        final float d = a.getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, (int) (8 * d));
        scroll.addView(box);

        for (int i = 0; i < m.pollOptions.size(); i++) {
            List<String> voters = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> e : m.pollVotes.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue().contains(i)) voters.add(e.getKey());
            }
            TextView header = new TextView(a);
            String opt = m.pollOptions.get(i) != null ? m.pollOptions.get(i) : "";
            header.setText(opt + "  \u00b7  " + voters.size());
            header.setTextSize(14);
            header.setTypeface(header.getTypeface(), Typeface.BOLD);
            header.setPadding(0, (int) (i == 0 ? 4 * d : 14 * d), 0, (int) (2 * d));
            box.addView(header);
            for (String uid : voters) {
                String name = names.nameOf(uid);
                View row = rows != null ? rows.create(uid, name) : null;
                if (row == null) {
                    TextView t = new TextView(a);
                    t.setText(name);
                    t.setTextSize(14);
                    t.setPadding((int) (8 * d), (int) (4 * d), 0, (int) (4 * d));
                    row = t;
                }
                box.addView(row);
            }
        }
        AlertDialogStyler.showRounded(
                new AlertDialog.Builder(a)
                        .setTitle(R.string.poll_voters_title)
                        .setView(scroll)
                        .setPositiveButton(R.string.poll_voters_close, null)
                        .create(),
                AlertDialogStyler.DialogSize.WIDE);
    }
}
