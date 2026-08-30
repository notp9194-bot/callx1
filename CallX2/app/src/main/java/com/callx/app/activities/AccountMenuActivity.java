package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.cache.MiscAvatarBinder;
import com.callx.app.R;
import com.callx.app.databinding.ActivityAccountMenuBinding;
import com.callx.app.utils.BiometricLoginManager;
import com.callx.app.utils.AccountSessionStore;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.hub.GamesHubActivity;

public class AccountMenuActivity extends AppCompatActivity {

    private ActivityAccountMenuBinding binding;
    private String myCallxId = "", myUid = "", myName = "", myPhoto = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccountMenuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.llHeaderAvatar.setOnClickListener(v -> openMyReelsProfile());
        loadProfile();
        setupMenuRows();
    }

    @Override protected void onResume() { super.onResume(); loadProfile(); }

    @Override
    protected void onDestroy() {
        // FIX (Lifecycle-aware cancel): stop any in-flight hero-avatar
        // request now that this screen is going away for good.
        MiscAvatarBinder.cancel(this, binding.ivProfileAvatar);
        MiscAvatarBinder.cancel(this, binding.ivHeaderAvatar);
        super.onDestroy();
    }

    private void loadProfile() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        myUid = uid;
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String name  = orEmpty(snap.child("name").getValue(String.class));
                String about = orEmpty(snap.child("about").getValue(String.class));
                String photo = orEmpty(snap.child("photoUrl").getValue(String.class));
                String thumb = orEmpty(snap.child("thumbUrl").getValue(String.class));
                Long   avatarVerVal = snap.child("avatarVersion").getValue(Long.class);
                long   avatarVer = avatarVerVal == null ? 0L : avatarVerVal;
                myCallxId = orEmpty(snap.child("callxId").getValue(String.class));
                myName = name; myPhoto = photo;
                AccountSessionStore.remember(
                    AccountMenuActivity.this,
                    uid,
                    name,
                    FirebaseAuth.getInstance().getCurrentUser() == null
                        ? "" : FirebaseAuth.getInstance().getCurrentUser().getEmail(),
                    snap.child("phone").getValue(String.class),
                    thumb.isEmpty() ? photo : thumb,
                    snap.child("loginType").getValue(String.class),
                    myCallxId);
                binding.tvProfileName.setText(name.isEmpty() ? "User" : name);
                binding.tvProfileAbout.setText(about.isEmpty() ? "Hey there! I am using CallX" : about);
                binding.tvCallxId.setText("ID: " + (myCallxId.isEmpty() ? "—" : myCallxId));
                // FIX (deep avatar pipeline): was a flat Glide.load().override(240,240)
                // with no shared tier bucket, no L2/L3 reuse, no lifecycle-aware
                // cancel — see MiscAvatarBinder for the full rationale.
                String profileAvatar = thumb.isEmpty() ? photo : thumb;
                MiscAvatarBinder.bind(AccountMenuActivity.this, binding.ivProfileAvatar,
                    profileAvatar, avatarVer, MiscAvatarBinder.HERO_TIER, R.drawable.ic_person);
                String headerImg = thumb.isEmpty() ? photo : thumb;
                MiscAvatarBinder.bind(AccountMenuActivity.this, binding.ivHeaderAvatar,
                    headerImg, avatarVer, MiscAvatarBinder.HERO_TIER, R.drawable.ic_person);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void setupMenuRows() {
        binding.btnEditProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        binding.ivProfileAvatar.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        binding.btnCopyId.setOnClickListener(v -> copyCallxId());

        configureRow(binding.rowProfile.getRoot(), R.drawable.ic_person, "Edit Profile", "Name, photo, about");
        binding.rowProfile.getRoot().setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));

        configureRow(binding.rowAccountCenter.getRoot(), R.drawable.ic_person_add,
            "Account Center", "Add, switch, or remove accounts on this device");
        binding.rowAccountCenter.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, AccountCenterActivity.class)));

        configureRow(binding.rowCallxId.getRoot(), R.drawable.ic_person_add, "My CallX ID", myCallxId.isEmpty() ? "Tap to copy" : myCallxId);
        binding.rowCallxId.getRoot().setOnClickListener(v -> copyCallxId());

        configureRow(binding.rowPrivacy.getRoot(), R.drawable.ic_phone, "Privacy & Security", "App lock, fingerprint, PIN, pattern");
        binding.rowPrivacy.getRoot().setOnClickListener(v -> startActivity(new Intent(this, PrivacySecurityActivity.class)));

        configureRow(binding.rowLinkedDevices.getRoot(), R.drawable.ic_phone, "Linked Devices",
            "Link a phone, tablet, or another device");
        binding.rowLinkedDevices.getRoot().setOnClickListener(v -> startActivity(new Intent().setClassName(this, "com.callx.app.chat.linkeddevice.LinkedDevicesActivity")));

        configureRow(binding.rowNotifications.getRoot(), R.drawable.ic_status_notification, "Notifications", "Message and call alerts");
        binding.rowNotifications.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, GlobalNotificationSettingsActivity.class)));

        configureRow(binding.rowChats.getRoot(), R.drawable.ic_message_notification, "Chats", "Chat history and media");
        binding.rowChats.getRoot().setOnClickListener(v ->
            Toast.makeText(this, "Chat settings — coming soon", Toast.LENGTH_SHORT).show());

        configureRow(binding.rowStorage.getRoot(), R.drawable.ic_file, "Storage & Cache", "Cache size, hit rate, clear cache");
        binding.rowStorage.getRoot().setOnClickListener(v -> startActivity(new Intent(this, CacheStatsActivity.class)));

        configureRow(binding.rowGames.getRoot(), R.drawable.ic_play, "Mini Games", "Fun games khelo — Bubble Pop aur aur bhi!");
        binding.rowGames.getRoot().setOnClickListener(v -> openGamesHub());

        configureRow(binding.rowHelp.getRoot(), R.drawable.ic_search, "Help Center", "FAQ, contact support");
        binding.rowHelp.getRoot().setOnClickListener(v ->
            Toast.makeText(this, "Help — coming soon", Toast.LENGTH_SHORT).show());

        configureRow(binding.rowAbout.getRoot(), R.drawable.ic_group, "About CallX", "App version and licenses");
        binding.rowAbout.getRoot().setOnClickListener(v -> showAboutDialog());

        // Logout
        configureRow(binding.rowLogout.getRoot(), R.drawable.ic_logout, "Logout", null);
        ((TextView) binding.rowLogout.getRoot().findViewById(R.id.tv_menu_title))
            .setTextColor(getColor(R.color.action_danger));
        binding.rowLogout.getRoot().setOnClickListener(v -> confirmLogout());

        // Account Delete (Play Store policy ke liye zaroori)
        try {
            View deleteRow = binding.rowDeleteAccount.getRoot();
            configureRow(deleteRow, R.drawable.ic_logout, "Account Delete karo", "Sab data permanently hata dega");
            ((TextView) deleteRow.findViewById(R.id.tv_menu_title))
                .setTextColor(getColor(R.color.action_danger));
            deleteRow.setOnClickListener(v -> confirmDeleteAccount());
        } catch (Exception ignored) {}
    }

    private void copyCallxId() {
        if (myCallxId.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("CallX ID", myCallxId));
        Toast.makeText(this, "CallX ID copied!", Toast.LENGTH_SHORT).show();
    }

    private void configureRow(View row, int iconRes, String title, String subtitle) {
        ((android.widget.ImageView) row.findViewById(R.id.iv_menu_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.tv_menu_title)).setText(title);
        TextView sub = row.findViewById(R.id.tv_menu_subtitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            sub.setText(subtitle); sub.setVisibility(View.VISIBLE);
        } else { sub.setVisibility(View.GONE); }
    }

    private void openGamesHub() {
        try {
            Class<?> cls = Class.forName("com.callx.app.hub.GamesHubActivity");
            startActivity(new Intent(this, cls));
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Games coming soon!", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMyReelsProfile() {
        if (myUid.isEmpty()) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("uid", myUid); i.putExtra("name", myName); i.putExtra("photo", myPhoto);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Reels not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmLogout() {
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(this,
                "account_logout", com.callx.app.utils.AlertDialogStyler.DialogSize.DEFAULT,
                "Logout", "Kya aap logout karna chahte hain?",
                "Logout", () -> {
                    // Fix #2: Logout pe biometric login disable karo (security)
                    BiometricLoginManager.getInstance(this).disable();
                    com.callx.app.utils.PresenceManager.getInstance().onLogout();
                    // WHATSAPP-LEVEL FIX: clear the plaintext Chat List
                    // instant-snapshot (see ChatSnapshotCache) so the next
                    // account that logs in on this device never flashes
                    // this account's chat previews as its own first frame.
                    com.callx.app.chatlist.ChatSnapshotCache.clearSnapshotAsync(this);
                    // FIX-ACCT-SWITCH: same reason as AuthActivity's
                    // EXTRA_FORCE_LOGIN branch — chats/messages carry no
                    // ownerUid in Room, so without wiping the DB here a
                    // different account logging in later on this same
                    // device would still see this account's cached chats.
                    com.callx.app.db.AppDatabase.wipeForAccountSwitch(this);
                    FirebaseAuth.getInstance().signOut();
                    Intent i = new Intent(this, AuthActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                },
                null, null,
                "Cancel");
    }

    private void confirmDeleteAccount() {
        new MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Account Delete karo?")
            .setMessage("Yeh action permanent hai!\n\n• Aapka pura account delete ho jaayega\n• Saare messages aur data hata diye jaayenge\n• Yeh recover nahi hoga\n\nKya aap sure hain?")
            .setPositiveButton("Haan, Delete karo", (d, w) -> deleteAccount())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteAccount() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();

        // Firebase Database se user data hata do
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(uid).removeValue();

        // Firebase Auth account delete karo
        user.delete()
            .addOnSuccessListener(x -> {
                // Fix #2: Account delete pe bhi biometric disable karo
                BiometricLoginManager.getInstance(this).disable();
                AccountSessionStore.remove(this, uid);
                // WHATSAPP-LEVEL FIX: same reasoning as logout — don't let
                // the deleted account's chat previews survive in the
                // plaintext snapshot for whoever logs in next.
                com.callx.app.chatlist.ChatSnapshotCache.clearSnapshotAsync(this);
                Toast.makeText(this, "Account delete ho gaya", Toast.LENGTH_LONG).show();
                Intent i = new Intent(this, AuthActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            })
            .addOnFailureListener(e -> {
                // Re-authentication ki zaroorat ho sakti hai
                Toast.makeText(this,
                    "Delete fail hua. Pehle logout karke dobara login karo phir try karo.",
                    Toast.LENGTH_LONG).show();
            });
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
            .setTitle("About CallX")
            .setMessage("CallX v3.1.0\n\nProduction-grade messaging and video calling app.\n\nBuilt with Firebase + WebRTC")
            .setPositiveButton("OK", null).show();
    }

    private String orEmpty(String s) { return s == null ? "" : s; }
}
