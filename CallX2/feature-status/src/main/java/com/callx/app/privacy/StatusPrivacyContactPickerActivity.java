package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusPrivacyContactPickerActivity — Multi-select contact picker for privacy lists.
 *
 * Used for:
 *  MODE_EXCEPT      → select contacts to EXCLUDE from seeing status
 *  MODE_ONLY        → select contacts ALLOWED to see status
 *  MODE_CLOSE_FRIENDS → manage your close friends group
 *
 * Returns: ArrayList<String> of selected UIDs via RESULT_OK intent.
 */
public class StatusPrivacyContactPickerActivity extends AppCompatActivity {

    private RecyclerView        rvContacts;
    private EditText            etSearch;
    private View                btnDone;
    private TextView            tvTitle, tvSubtitle;

    private List<ContactItem>   allContacts  = new ArrayList<>();
    private List<ContactItem>   filtered     = new ArrayList<>();
    private Set<String>         selectedUids = new HashSet<>();
    private String              mode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_status_privacy_contact_picker);

        mode = getIntent().getStringExtra("mode");
        List<String> preSelected = getIntent().getStringArrayListExtra("selected");
        if (preSelected != null) selectedUids.addAll(preSelected);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getTitleForMode(mode));
        }

        rvContacts = fv("rv_contacts");
        etSearch   = fv("et_search");
        btnDone    = fv("btn_done");
        tvTitle    = fv("tv_picker_title");
        tvSubtitle = fv("tv_picker_subtitle");

        if (tvTitle    != null) tvTitle.setText(getTitleForMode(mode));
        if (tvSubtitle != null) tvSubtitle.setText(getSubtitleForMode(mode));

        if (rvContacts != null)
            rvContacts.setLayoutManager(new LinearLayoutManager(this));

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    filterContacts(s.toString().trim());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        if (btnDone != null) btnDone.setOnClickListener(v -> finishWithResult());

        loadContacts();
    }

    private void loadContacts() {
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }
        FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allContacts.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        String uid  = c.getKey();
                        String name = c.child("name").getValue(String.class);
                        String photo= c.child("photoUrl").getValue(String.class);
                        if (uid != null)
                            allContacts.add(new ContactItem(uid,
                                name != null ? name : uid,
                                photo != null ? photo : ""));
                    }
                    allContacts.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                    filtered = new ArrayList<>(allContacts);
                    runOnUiThread(() -> {
                        if (rvContacts != null) rvContacts.setAdapter(buildAdapter());
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void filterContacts(String query) {
        filtered.clear();
        if (query.isEmpty()) { filtered.addAll(allContacts); }
        else {
            String q = query.toLowerCase();
            for (ContactItem c : allContacts)
                if (c.name.toLowerCase().contains(q)) filtered.add(c);
        }
        if (rvContacts != null && rvContacts.getAdapter() != null)
            rvContacts.getAdapter().notifyDataSetChanged();
    }

    private RecyclerView.Adapter<RecyclerView.ViewHolder> buildAdapter() {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                LinearLayout row = new LinearLayout(StatusPrivacyContactPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(16, 16, 16, 16);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT);
                row.setLayoutParams(lp);
                de.hdodenhof.circleimageview.CircleImageView iv =
                    new de.hdodenhof.circleimageview.CircleImageView(
                        StatusPrivacyContactPickerActivity.this);
                iv.setId(android.R.id.icon);
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(48, 48);
                iv.setLayoutParams(ivLp);
                row.addView(iv);
                TextView tv = new TextView(StatusPrivacyContactPickerActivity.this);
                tv.setId(android.R.id.text1);
                LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvLp.leftMargin = 16; tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
                tv.setLayoutParams(tvLp);
                row.addView(tv);
                CheckBox cb = new CheckBox(StatusPrivacyContactPickerActivity.this);
                cb.setId(android.R.id.checkbox);
                row.addView(cb);
                return new RecyclerView.ViewHolder(row) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                ContactItem item = filtered.get(pos);
                de.hdodenhof.circleimageview.CircleImageView iv =
                    h.itemView.findViewById(android.R.id.icon);
                TextView tv = h.itemView.findViewById(android.R.id.text1);
                CheckBox cb = h.itemView.findViewById(android.R.id.checkbox);
                tv.setText(item.name);
                if (!item.photoUrl.isEmpty())
                    Glide.with(StatusPrivacyContactPickerActivity.this)
                        .load(item.photoUrl).circleCrop().into(iv);
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(selectedUids.contains(item.uid));
                cb.setOnCheckedChangeListener((b, checked) -> {
                    if (checked) selectedUids.add(item.uid);
                    else         selectedUids.remove(item.uid);
                });
                h.itemView.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            }
            @Override public int getItemCount() { return filtered.size(); }
        };
    }

    private void finishWithResult() {
        Intent result = new Intent();
        result.putStringArrayListExtra("selected", new ArrayList<>(selectedUids));
        result.putExtra("mode", mode);
        setResult(RESULT_OK, result);
        finish();
    }

    private String getTitleForMode(String m) {
        if (m == null) return "Select Contacts";
        switch (m) {
            case "except":        return "Contacts to Exclude";
            case "only":          return "Contacts to Include";
            case "close_friends": return "Close Friends";
            default: return "Select Contacts";
        }
    }

    private String getSubtitleForMode(String m) {
        if (m == null) return "";
        switch (m) {
            case "except":        return "These contacts won't see your status";
            case "only":          return "Only these contacts will see your status";
            case "close_friends": return "Your close friends group";
            default: return "";
        }
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    static class ContactItem {
        String uid, name, photoUrl;
        ContactItem(String uid, String name, String photoUrl) {
            this.uid = uid; this.name = name; this.photoUrl = photoUrl;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T fv(String name) {
        int id = getResources().getIdentifier(name, "id", getPackageName());
        if (id == 0) return null;
        return (T) findViewById(id);
    }
}
