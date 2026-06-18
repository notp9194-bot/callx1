package com.callx.app.activities;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.utils.StatusPrivacyManager;

/**
 * StatusPrivacySettingsActivity — Global default privacy settings for statuses.
 *
 * The user sets their DEFAULT privacy mode here.
 * Per-status overrides are available in NewStatusActivity.
 *
 * UI:
 *  RadioGroup with 5 options:
 *    ◉ Everyone
 *    ◉ My Contacts (default)
 *    ◉ My Contacts Except… [→ contact picker, shows count badge]
 *    ◉ Only Share With…    [→ contact picker, shows count badge]
 *    ◉ Close Friends Only  [→ close friends manager]
 *
 *  Bottom: "Close Friends" section with a button to open close-friends manager
 */
public class StatusPrivacySettingsActivity extends AppCompatActivity {

    private RadioGroup    rgPrivacy;
    private RadioButton   rbEveryone, rbContacts, rbExcept, rbOnly, rbCloseFriends;
    private TextView      tvExceptCount, tvOnlyCount, tvCloseFriendsCount;
    private View          btnManageExcept, btnManageOnly, btnManageCloseFriends;

    private StatusPrivacyManager privacyMgr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_status_privacy_settings);

        privacyMgr = StatusPrivacyManager.get(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Status Privacy");
        }

        bindViews();
        loadCurrentSettings();
        setupListeners();
    }

    private void bindViews() {
        rgPrivacy           = fv("rg_privacy");
        rbEveryone          = fv("rb_everyone");
        rbContacts          = fv("rb_contacts");
        rbExcept            = fv("rb_except");
        rbOnly              = fv("rb_only");
        rbCloseFriends      = fv("rb_close_friends");
        tvExceptCount       = fv("tv_except_count");
        tvOnlyCount         = fv("tv_only_count");
        tvCloseFriendsCount = fv("tv_close_friends_count");
        btnManageExcept     = fv("btn_manage_except");
        btnManageOnly       = fv("btn_manage_only");
        btnManageCloseFriends = fv("btn_manage_close_friends");
    }

    private void loadCurrentSettings() {
        String mode = privacyMgr.getDefaultMode();
        if (rbEveryone     != null && StatusPrivacyManager.MODE_EVERYONE.equals(mode))
            rbEveryone.setChecked(true);
        else if (rbContacts != null && StatusPrivacyManager.MODE_CONTACTS.equals(mode))
            rbContacts.setChecked(true);
        else if (rbExcept   != null && StatusPrivacyManager.MODE_EXCEPT.equals(mode))
            rbExcept.setChecked(true);
        else if (rbOnly     != null && StatusPrivacyManager.MODE_ONLY.equals(mode))
            rbOnly.setChecked(true);
        else if (rbCloseFriends != null && StatusPrivacyManager.MODE_CLOSE_FRIENDS.equals(mode))
            rbCloseFriends.setChecked(true);

        updateCounts();
    }

    private void updateCounts() {
        int exceptCnt       = privacyMgr.getExceptList().size();
        int onlyCnt         = privacyMgr.getOnlyList().size();
        int closeFriendsCnt = privacyMgr.getCloseFriends().size();

        if (tvExceptCount != null)
            tvExceptCount.setText(exceptCnt > 0 ? "(" + exceptCnt + " excluded)" : "");
        if (tvOnlyCount != null)
            tvOnlyCount.setText(onlyCnt > 0 ? "(" + onlyCnt + " contacts)" : "");
        if (tvCloseFriendsCount != null)
            tvCloseFriendsCount.setText(closeFriendsCnt > 0
                ? closeFriendsCnt + " friends" : "No close friends added");
    }

    private void setupListeners() {
        if (rgPrivacy != null) {
            rgPrivacy.setOnCheckedChangeListener((group, id) -> {
                String mode;
                if (rbEveryone     != null && id == rbEveryone.getId())      mode = StatusPrivacyManager.MODE_EVERYONE;
                else if (rbExcept  != null && id == rbExcept.getId())        mode = StatusPrivacyManager.MODE_EXCEPT;
                else if (rbOnly    != null && id == rbOnly.getId())          mode = StatusPrivacyManager.MODE_ONLY;
                else if (rbCloseFriends != null && id == rbCloseFriends.getId()) mode = StatusPrivacyManager.MODE_CLOSE_FRIENDS;
                else mode = StatusPrivacyManager.MODE_CONTACTS;
                privacyMgr.setDefaultMode(mode);
                Toast.makeText(this, "Default privacy: " +
                    StatusPrivacyManager.getModeLabel(mode), Toast.LENGTH_SHORT).show();
            });
        }

        if (btnManageExcept != null)
            btnManageExcept.setOnClickListener(v -> openContactPicker(StatusPrivacyManager.MODE_EXCEPT));
        if (btnManageOnly != null)
            btnManageOnly.setOnClickListener(v -> openContactPicker(StatusPrivacyManager.MODE_ONLY));
        if (btnManageCloseFriends != null)
            btnManageCloseFriends.setOnClickListener(v ->
                openContactPicker(StatusPrivacyManager.MODE_CLOSE_FRIENDS));
    }

    private void openContactPicker(String mode) {
        android.content.Intent i = new android.content.Intent(
            this, StatusPrivacyContactPickerActivity.class);
        i.putExtra("mode", mode);
        java.util.ArrayList<String> current = new java.util.ArrayList<>(
            mode.equals(StatusPrivacyManager.MODE_EXCEPT)
                ? privacyMgr.getExceptList()
                : mode.equals(StatusPrivacyManager.MODE_ONLY)
                    ? privacyMgr.getOnlyList()
                    : privacyMgr.getCloseFriends());
        i.putStringArrayListExtra("selected", current);
        startActivityForResult(i, 100);
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        java.util.List<String> selected = data.getStringArrayListExtra("selected");
        if (selected == null) return;
        // Re-read mode from activity result extra
        String mode = data.getStringExtra("mode");
        if (mode == null) return;
        java.util.HashSet<String> set = new java.util.HashSet<>(selected);
        if (mode.equals(StatusPrivacyManager.MODE_EXCEPT))        privacyMgr.setExceptList(set);
        else if (mode.equals(StatusPrivacyManager.MODE_ONLY))     privacyMgr.setOnlyList(set);
        else if (mode.equals(StatusPrivacyManager.MODE_CLOSE_FRIENDS)) privacyMgr.setCloseFriends(set);
        updateCounts();
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @SuppressWarnings("unchecked")
    private <T extends View> T fv(String name) {
        int id = getResources().getIdentifier(name, "id", getPackageName());
        if (id == 0) return null;
        return (T) findViewById(id);
    }
}
