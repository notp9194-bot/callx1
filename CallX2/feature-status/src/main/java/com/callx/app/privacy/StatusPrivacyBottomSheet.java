package com.callx.app.privacy;
import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusCloseFriendsManager;
import com.callx.app.utils.StatusPrivacyManager;
import com.google.firebase.database.*;
import java.util.*;
/**
 * StatusPrivacyBottomSheet v25 — Full privacy selector.
 * FIX: "Except/Only" modes now open contact multi-selection UI.
 * NEW: Close Friends mode with its own managed list.
 * Persists selection to StatusPrivacyManager + FirebaseUtils.
 */
public class StatusPrivacyBottomSheet {
    public interface OnPrivacySelected {
        void onSelected(String mode, Set<String> selectedUids);
    }
    public static void show(Context ctx, String myUid, OnPrivacySelected cb) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = buildRootView(ctx);
        String[] modes   = {
            StatusPrivacyManager.MODE_EVERYONE,
            StatusPrivacyManager.MODE_CONTACTS,
            "close_friends",
            StatusPrivacyManager.MODE_EXCEPT,
            StatusPrivacyManager.MODE_ONLY
        };
        String[] labels  = {"Everyone", "My contacts", "Close friends ⭐", "Contacts except…", "Only share with…"};
        String[] descs   = {
            "Anyone can see your status",
            "Only your contacts can see",
            "Only your close friends",
            "All contacts except selected",
            "Only selected contacts"
        };
        String current = StatusPrivacyManager.get(ctx).getDefaultMode();
        TextView title = makeTv(ctx, "Who can see your status?", 18, true);
        title.setPadding(0, dp(ctx,4), 0, dp(ctx,16));
        root.addView(title);
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            boolean selected  = mode.equals(current);
            LinearLayout row  = buildRow(ctx, labels[i], descs[i], selected);
            row.setOnClickListener(v -> {
                if (mode.equals(StatusPrivacyManager.MODE_EXCEPT)
                        || mode.equals(StatusPrivacyManager.MODE_ONLY)
                        || mode.equals("close_friends")) {
                    sheet.dismiss();
                    showContactPicker(ctx, myUid, mode, cb);
                } else {
                    StatusPrivacyManager.get(ctx).setDefaultMode(mode);
                    if (cb != null) cb.onSelected(mode, Collections.emptySet());
                    sheet.dismiss();
                }
            });
            root.addView(row);
        }
        sheet.setContentView(new ScrollView(ctx) {{ addView(root); }});
        sheet.show();
    }
    private static void showContactPicker(Context ctx, String myUid,
                                           String mode, OnPrivacySelected cb) {
        BottomSheetDialog picker = new BottomSheetDialog(ctx);
        LinearLayout root = buildRootView(ctx);
        String label = mode.equals(StatusPrivacyManager.MODE_EXCEPT)
                ? "Exclude these contacts" : mode.equals("close_friends")
                ? "Select close friends ⭐" : "Share only with";
        root.addView(makeTv(ctx, label, 17, true));
        Set<String> initial = mode.equals(StatusPrivacyManager.MODE_EXCEPT)
                ? StatusPrivacyManager.get(ctx).getExceptList()
                : mode.equals(StatusPrivacyManager.MODE_ONLY)
                ? StatusPrivacyManager.get(ctx).getOnlyList()
                : StatusCloseFriendsManager.getLocalList(ctx);
        Set<String> selected = new HashSet<>(initial);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        Button done = new Button(ctx);
        done.setText("Done (" + selected.size() + " selected)");
        // Load contacts
        FirebaseUtils.getContactsRef(myUid).get().addOnSuccessListener(snap -> {
            for (DataSnapshot c : snap.getChildren()) {
                String uid   = c.getKey();
                String name  = c.child("name").getValue(String.class);
                String photo = c.child("photoUrl").getValue(String.class);
                if (uid == null) continue;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(ctx,10), 0, dp(ctx,10));
                CheckBox cb2 = new CheckBox(ctx);
                cb2.setChecked(selected.contains(uid));
                de.hdodenhof.circleimageview.CircleImageView av =
                    new de.hdodenhof.circleimageview.CircleImageView(ctx);
                int sz = dp(ctx,40);
                av.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                if (photo != null) Glide.with(ctx).load(photo).into(av);
                else av.setImageResource(android.R.drawable.ic_menu_my_calendar);
                TextView tv = new TextView(ctx);
                tv.setText(name != null ? name : uid);
                tv.setTextSize(15);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tlp.setMarginStart(dp(ctx,12));
                tv.setLayoutParams(tlp);
                row.addView(cb2); row.addView(av); row.addView(tv);
                String fuid = uid;
                row.setOnClickListener(v -> {
                    if (selected.contains(fuid)) selected.remove(fuid);
                    else selected.add(fuid);
                    cb2.setChecked(selected.contains(fuid));
                    done.setText("Done (" + selected.size() + " selected)");
                });
                list.addView(row);
            }
        });
        done.setOnClickListener(v -> {
            if (mode.equals(StatusPrivacyManager.MODE_EXCEPT)) {
                StatusPrivacyManager.get(ctx).setExceptList(selected);
            } else if (mode.equals(StatusPrivacyManager.MODE_ONLY)) {
                StatusPrivacyManager.get(ctx).setOnlyList(selected);
            } else if (mode.equals("close_friends")) {
                // Sync close friends list
                for (String uid : selected) StatusCloseFriendsManager.addCloseFriend(ctx, myUid, uid);
            }
            StatusPrivacyManager.get(ctx).setDefaultMode(mode);
            if (cb != null) cb.onSelected(mode, selected);
            picker.dismiss();
        });
        ScrollView sv = new ScrollView(ctx);
        sv.addView(list);
        root.addView(sv);
        root.addView(done);
        picker.setContentView(root);
        picker.show();
    }
    private static LinearLayout buildRootView(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(ctx,20), dp(ctx,12), dp(ctx,20), dp(ctx,32));
        return r;
    }
    private static LinearLayout buildRow(Context ctx, String label, String desc, boolean selected) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(ctx,16), dp(ctx,14), dp(ctx,16), dp(ctx,14));
        if (selected) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#1A6200EE"));
            bg.setCornerRadius(dp(ctx,12));
            row.setBackground(bg);
        }
        TextView lv = new TextView(ctx);
        lv.setText(selected ? "✓  " + label : "    " + label);
        lv.setTextSize(15);
        lv.setTypeface(null, android.graphics.Typeface.BOLD);
        if (selected) lv.setTextColor(Color.parseColor("#6200EE"));
        TextView dv = new TextView(ctx);
        dv.setText(desc);
        dv.setTextSize(12);
        dv.setTextColor(Color.GRAY);
        dv.setPadding(dp(ctx,28), 0, 0, 0);
        row.addView(lv); row.addView(dv);
        return row;
    }
    private static TextView makeTv(Context ctx, String text, int size, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(size);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}