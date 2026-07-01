package com.callx.app.chat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Full-screen contact picker.
 *
 * Returns:
 *   EXTRA_CONTACT_NAME   (String)
 *   EXTRA_CONTACT_PHONE  (String)  — primary phone
 *   EXTRA_CONTACT_PHONE2 (String)  — secondary phone (may be null)
 *
 * Usage:
 *   startActivityForResult(
 *       new Intent(this, ContactSharePickerActivity.class),
 *       REQUEST_PICK_CONTACT);
 *
 *   @Override onActivityResult → if resultCode == RESULT_OK grab extras above.
 */
public class ContactSharePickerActivity extends AppCompatActivity {

    public static final String EXTRA_CONTACT_NAME   = "contact_name";
    public static final String EXTRA_CONTACT_PHONE  = "contact_phone";
    public static final String EXTRA_CONTACT_PHONE2 = "contact_phone2";

    private static final int REQ_PERM = 101;

    private final List<DeviceContact> allContacts      = new ArrayList<>();
    private final List<DeviceContact> filteredContacts = new ArrayList<>();
    private ContactPickerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Minimal layout built programmatically to avoid extra layout file dependency
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Toolbar ──────────────────────────────────────────────────────────
        android.widget.LinearLayout toolbar = new android.widget.LinearLayout(this);
        toolbar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setBackgroundColor(resolveAttrColor(com.google.android.material.R.attr.colorSurface));

        ImageView btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnBack.setOnClickListener(v -> finish());
        toolbar.addView(btnBack, new android.widget.LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Contact bhejo");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.LinearLayout.LayoutParams titleLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(8));
        toolbar.addView(tvTitle, titleLp);

        root.addView(toolbar, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Search bar ────────────────────────────────────────────────────────
        EditText etSearch = new EditText(this);
        etSearch.setHint("Naam se search karo…");
        etSearch.setSingleLine(true);
        etSearch.setCompoundDrawablePadding(dp(8));
        int searchPad = dp(12);
        etSearch.setPadding(searchPad, searchPad, searchPad, searchPad);
        root.addView(etSearch, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── RecyclerView ──────────────────────────────────────────────────────
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactPickerAdapter(filteredContacts, contact -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_CONTACT_NAME,   contact.name);
            result.putExtra(EXTRA_CONTACT_PHONE,  contact.phone);
            result.putExtra(EXTRA_CONTACT_PHONE2, contact.phone2);
            setResult(RESULT_OK, result);
            finish();
        });
        rv.setAdapter(adapter);
        root.addView(rv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterContacts(s.toString().trim());
            }
        });

        checkPermissionAndLoad();
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private void checkPermissionAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        } else {
            Toast.makeText(this, "Contacts permission nahi mila", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ── Contact loading ───────────────────────────────────────────────────────

    private void loadContacts() {
        new Thread(() -> {
            Cursor cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                    },
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

            // Group multiple numbers per contact
            java.util.LinkedHashMap<String, DeviceContact> map = new java.util.LinkedHashMap<>();
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx  = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                int idIdx   = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                while (cursor.moveToNext()) {
                    String id    = cursor.getString(idIdx);
                    String name  = cursor.getString(nameIdx);
                    String phone = cursor.getString(numIdx);
                    if (phone == null || phone.isEmpty()) continue;
                    if (map.containsKey(id)) {
                        DeviceContact dc = map.get(id);
                        if (dc.phone2 == null) dc.phone2 = phone;
                    } else {
                        DeviceContact dc = new DeviceContact();
                        dc.name  = name != null ? name : "Unknown";
                        dc.phone = phone;
                        map.put(id, dc);
                    }
                }
                cursor.close();
            }

            allContacts.clear();
            allContacts.addAll(map.values());
            runOnUiThread(() -> filterContacts(""));
        }).start();
    }

    private void filterContacts(String query) {
        filteredContacts.clear();
        if (query.isEmpty()) {
            filteredContacts.addAll(allContacts);
        } else {
            String lq = query.toLowerCase();
            for (DeviceContact dc : allContacts) {
                if (dc.name.toLowerCase().contains(lq) || dc.phone.contains(lq)) {
                    filteredContacts.add(dc);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int resolveAttrColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    // ── Data model ────────────────────────────────────────────────────────────

    public static class DeviceContact {
        public String name;
        public String phone;
        public String phone2;
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class ContactPickerAdapter
            extends RecyclerView.Adapter<ContactPickerAdapter.VH> {

        interface OnPick { void onPick(DeviceContact c); }

        private final List<DeviceContact> items;
        private final OnPick              listener;

        ContactPickerAdapter(List<DeviceContact> items, OnPick listener) {
            this.items    = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(parent.getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = Math.round(12 * parent.getContext().getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            android.util.TypedValue rippleVal = new android.util.TypedValue();
            parent.getContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, rippleVal, true);
            row.setBackgroundResource(rippleVal.resourceId);

            int sz = Math.round(44 * parent.getContext().getResources().getDisplayMetrics().density);
            CircleImageView avatar = new CircleImageView(parent.getContext());
            avatar.setId(View.generateViewId());
            avatar.setImageResource(R.drawable.ic_person);
            row.addView(avatar, new android.widget.LinearLayout.LayoutParams(sz, sz));

            android.widget.LinearLayout textCol = new android.widget.LinearLayout(parent.getContext());
            textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
            int ml = Math.round(12 * parent.getContext().getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(ml);
            textCol.setLayoutParams(lp);

            TextView tvName = new TextView(parent.getContext());
            tvName.setId(View.generateViewId());
            tvName.setTextSize(15);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(tvName);

            TextView tvPhone = new TextView(parent.getContext());
            tvPhone.setId(View.generateViewId());
            tvPhone.setTextSize(13);
            textCol.addView(tvPhone);

            row.addView(textCol);

            row.setTag(new int[]{avatar.getId(), tvName.getId(), tvPhone.getId()});

            ViewGroup.LayoutParams rowLp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            row.setLayoutParams(rowLp);

            return new VH(row, avatar, tvName, tvPhone);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DeviceContact c = items.get(pos);
            h.tvName.setText(c.name);
            h.tvPhone.setText(c.phone);
            h.itemView.setOnClickListener(v -> listener.onPick(c));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView avatar;
            TextView tvName, tvPhone;
            VH(View v, CircleImageView a, TextView n, TextView p) {
                super(v); avatar = a; tvName = n; tvPhone = p;
            }
        }
    }
}
