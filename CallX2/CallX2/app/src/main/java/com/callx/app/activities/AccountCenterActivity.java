package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.AccountSessionStore;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Instagram-style account hub. It intentionally delegates authentication to
 * AuthActivity/PhoneAuthActivity so every provider keeps the existing
 * Firebase security checks and verification screens.
 */
public class AccountCenterActivity extends AppCompatActivity {

    private LinearLayout accountList;
    private TextView currentSummary;
    private TextView emptyAccounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_center);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        accountList = findViewById(R.id.ll_saved_accounts);
        currentSummary = findViewById(R.id.tv_current_account_summary);
        emptyAccounts = findViewById(R.id.tv_no_saved_accounts);

        findViewById(R.id.btn_add_account).setOnClickListener(v -> openAuthForAccount(null));
        rememberCurrentProfileThenRender();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAccounts();
    }

    private void rememberCurrentProfileThenRender() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        AccountSessionStore.rememberFirebaseUser(this, user);
        FirebaseUtils.getUserRef(user.getUid()).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        AccountSessionStore.remember(
                                AccountCenterActivity.this,
                                user.getUid(),
                                snapshot.child("name").getValue(String.class),
                                user.getEmail(),
                                snapshot.child("phone").getValue(String.class),
                                firstNonEmpty(
                                        snapshot.child("thumbUrl").getValue(String.class),
                                        snapshot.child("photoUrl").getValue(String.class)),
                                snapshot.child("loginType").getValue(String.class),
                                snapshot.child("callxId").getValue(String.class));
                        renderAccounts();
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        renderAccounts();
                    }
                });
        renderAccounts();
    }

    private void renderAccounts() {
        if (accountList == null) return;
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        String currentUid = currentUser == null ? "" : currentUser.getUid();
        List<AccountSessionStore.Account> accounts = AccountSessionStore.getAccounts(this);

        accountList.removeAllViews();
        emptyAccounts.setVisibility(accounts.isEmpty() ? View.VISIBLE : View.GONE);

        AccountSessionStore.Account current = null;
        for (AccountSessionStore.Account account : accounts) {
            if (account.uid.equals(currentUid)) {
                current = account;
                break;
            }
        }
        currentSummary.setText(current == null
                ? "Current account"
                : "Signed in as " + current.displayName());

        for (AccountSessionStore.Account account : accounts) {
            addAccountRow(account, account.uid.equals(currentUid));
        }
    }

    private void addAccountRow(AccountSessionStore.Account account, boolean isCurrent) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_saved_account, accountList, false);
        CircleImageView avatar = row.findViewById(R.id.iv_saved_account_avatar);
        TextView name = row.findViewById(R.id.tv_saved_account_name);
        TextView identifier = row.findViewById(R.id.tv_saved_account_identifier);
        TextView provider = row.findViewById(R.id.tv_saved_account_provider);
        TextView action = row.findViewById(R.id.tv_saved_account_action);
        ImageButton remove = row.findViewById(R.id.btn_remove_saved_account);

        name.setText(account.displayName());
        identifier.setText(account.identifier());
        provider.setText(account.provider.isEmpty() ? "CallX account" : account.provider + " login");
        action.setText(isCurrent ? "Current" : "Switch");
        action.setTextColor(getColor(isCurrent ? R.color.brand_primary : R.color.text_secondary));

        if (!account.photoUrl.isEmpty()) {
            Glide.with(this)
                    .load(account.photoUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .into(avatar);
        } else {
            avatar.setImageResource(R.drawable.ic_person);
        }

        remove.setVisibility(isCurrent ? View.GONE : View.VISIBLE);
        View.OnClickListener switchListener = v -> {
            if (!isCurrent) confirmSwitch(account);
        };
        row.setOnClickListener(switchListener);
        action.setOnClickListener(switchListener);
        remove.setOnClickListener(v -> confirmRemove(account));
        accountList.addView(row);
    }

    private void confirmSwitch(AccountSessionStore.Account account) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Switch account?")
                .setMessage("CallX ko " + account.displayName()
                        + " ke account par chalane ke liye login verify karna hoga.")
                .setPositiveButton("Continue", (dialog, which) -> openAuthForAccount(account))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(AccountSessionStore.Account account) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove account from this device?")
                .setMessage("Isse sirf saved account shortcut hatega. CallX account ya uska data delete nahi hoga.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    AccountSessionStore.remove(this, account.uid);
                    renderAccounts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAuthForAccount(AccountSessionStore.Account account) {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.putExtra(AuthActivity.EXTRA_FORCE_LOGIN, true);
        if (account != null) {
            intent.putExtra(AuthActivity.EXTRA_SWITCH_UID, account.uid);
            intent.putExtra(AuthActivity.EXTRA_ACCOUNT_EMAIL, account.email);
            intent.putExtra(AuthActivity.EXTRA_ACCOUNT_PHONE, account.phone);
            intent.putExtra(AuthActivity.EXTRA_ACCOUNT_LABEL, account.displayName());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.trim().isEmpty() ? second : first;
    }
}