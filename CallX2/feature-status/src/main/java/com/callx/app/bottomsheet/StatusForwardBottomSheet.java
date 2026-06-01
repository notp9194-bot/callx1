package com.callx.app.bottomsheet;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusSeenTracker;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusForwardBottomSheet v25 — Forward a status to one or more contacts.
 * NEW: Multi-select contacts, search bar, send as chat message.
 */
public class StatusForwardBottomSheet {

    public static void show(Context ctx, StatusItem item, String myUid) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx,16), dp(ctx,8), dp(ctx,16), dp(ctx,24));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx,560)));

        // Title
        TextView title = new TextView(ctx);
        title.setText("Forward status");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(ctx,8), 0, dp(ctx,12));
        root.addView(title);

        // Search
        EditText search = new EditText(ctx);
        search.setHint("Search contacts…");
        search.setSingleLine(true);
        root.addView(search);

        // Contact list
        LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.addView(listContainer);
        root.addView(sv);

        // Send button
        Button sendBtn = new Button(ctx);
        sendBtn.setText("Send");
        sendBtn.setEnabled(false);
        root.addView(sendBtn);

        sheet.setContentView(root);
        sheet.show();

        Set<String> selected = new HashSet<>();
        List<String[]> allContacts = new ArrayList<>(); // [uid, name, photo]

        // Load contacts
        FirebaseUtils.getContactsRef(myUid).get().addOnSuccessListener(snap -> {
            for (DataSnapshot c : snap.getChildren()) {
                String uid = c.getKey();
                String name  = c.child("name").getValue(String.class);
                String photo = c.child("photoUrl").getValue(String.class);
                if (uid != null) allContacts.add(new String[]{uid, name != null ? name : uid, photo});
            }
            renderContacts(ctx, listContainer, allContacts, selected, sendBtn,
                    () -> {}, item, myUid, sheet);

            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    String q = s.toString().toLowerCase();
                    List<String[]> filtered = new ArrayList<>();
                    for (String[] contact : allContacts) {
                        if (contact[1].toLowerCase().contains(q)) filtered.add(contact);
                    }
                    listContainer.removeAllViews();
                    renderContacts(ctx, listContainer, filtered, selected, sendBtn,
                            () -> {}, item, myUid, sheet);
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            sendBtn.setOnClickListener(v -> {
                for (String uid : selected) {
                    sendForwardMessage(myUid, uid, item);
                }
                StatusSeenTracker.incrementForwardCount(item.ownerUid, item.id);
                Toast.makeText(ctx, "Forwarded to " + selected.size() + " contact(s)",
                        Toast.LENGTH_SHORT).show();
                sheet.dismiss();
            });
        });
    }

    private static void renderContacts(Context ctx, LinearLayout container,
                                        List<String[]> contacts, Set<String> selected,
                                        Button sendBtn, Runnable onToggle,
                                        StatusItem item, String myUid, BottomSheetDialog sheet) {
        container.removeAllViews();
        for (String[] c : contacts) {
            String uid = c[0], name = c[1], photo = c[2];
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(ctx,10), 0, dp(ctx,10));

            CheckBox cb = new CheckBox(ctx);
            cb.setChecked(selected.contains(uid));

            de.hdodenhof.circleimageview.CircleImageView av =
                new de.hdodenhof.circleimageview.CircleImageView(ctx);
            int sz = dp(ctx,40);
            av.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            if (photo != null && !photo.isEmpty()) Glide.with(ctx).load(photo).into(av);
            else av.setImageResource(android.R.drawable.ic_menu_my_calendar);

            TextView tv = new TextView(ctx);
            tv.setText(name);
            tv.setTextSize(15);
            LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvlp.setMarginStart(dp(ctx,12));
            tv.setLayoutParams(tvlp);

            row.addView(cb);
            row.addView(av);
            row.addView(tv);

            String fuid = uid;
            row.setOnClickListener(v -> {
                if (selected.contains(fuid)) selected.remove(fuid);
                else selected.add(fuid);
                cb.setChecked(selected.contains(fuid));
                sendBtn.setEnabled(!selected.isEmpty());
            });
            container.addView(row);
        }
    }

    private static void sendForwardMessage(String myUid, String toUid, StatusItem item) {
        String chatId = myUid.compareTo(toUid) < 0
                ? myUid + "_" + toUid : toUid + "_" + myUid;
        DatabaseReference ref = FirebaseUtils.db().getReference("messages").child(chatId);
        String msgId = ref.push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",        msgId);
        msg.put("senderId",  myUid);
        msg.put("type",      "forwarded_status");
        msg.put("text",      item.text != null ? item.text : item.caption != null ? item.caption : "");
        msg.put("mediaUrl",  item.mediaUrl);
        msg.put("thumbUrl",  item.thumbnailUrl);
        msg.put("statusType", item.type);
        msg.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen",      false);
        ref.child(msgId).setValue(msg);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
