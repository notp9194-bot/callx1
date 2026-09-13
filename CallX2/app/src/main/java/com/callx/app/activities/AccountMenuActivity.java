package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Map;
import java.util.HashMap;
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
import com.callx.app.analytics.ReelCreatorDashboardActivity;
import com.callx.app.creator.ReelMonetizationActivity;
import com.callx.app.creator.MilestoneEarningsActivity;
import com.callx.app.creator.VerifiedBadgeActivity;
import com.callx.app.creator.StarTalentActivity;

public class AccountMenuActivity extends AppCompatActivity {

    private ActivityAccountMenuBinding binding;
    private String myUsername = "", myUid = "", myName = "", myPhoto = "";
    // Step 5: last time this account's username was changed (ServerValue
    // .TIMESTAMP, ms) — 0 means never explicitly changed (still the one
    // chosen at signup/migration). Drives the cooldown in
    // showChangeUsernameDialog().
    private long myUsernameChangedAt = 0L;

    // ── Step 5 dialog state (debounced availability check, mirrors the
    // pattern in ProfileSetupActivity) ─────────────────────────────────────
    private final android.os.Handler usernameCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingUsernameCheck;
    private String lastCheckedUsername = null;
    private boolean usernameAvailable = false;
    private long usernameCheckToken = 0;

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
        if (pendingUsernameCheck != null) usernameCheckHandler.removeCallbacks(pendingUsernameCheck);
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
                // Step 3 (display swap): username is the public handle shown
                // on this screen now. callxId (the phone number) is read
                // separately below purely for AccountSessionStore — the
                // Account Center's account-switcher list is the one place
                // per the plan that's allowed to keep using it internally.
                myUsername = orEmpty(snap.child("username").getValue(String.class));
                Long changedAtVal = snap.child("usernameChangedAt").getValue(Long.class);
                myUsernameChangedAt = changedAtVal == null ? 0L : changedAtVal;
                String callxIdForAccountCenter = orEmpty(snap.child("callxId").getValue(String.class));
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
                    callxIdForAccountCenter);
                binding.tvProfileName.setText(name.isEmpty() ? "User" : name);
                binding.tvProfileAbout.setText(about.isEmpty() ? "Hey there! I am using CallX" : about);
                binding.tvUsername.setText(myUsername.isEmpty() ? "@—" : "@" + myUsername);
                // Row text is set once in setupMenuRows() before this async
                // load completes — refresh it here too so it doesn't stay
                // stuck on stale text for the rest of the screen's life.
                configureRow(binding.rowUsername.getRoot(), R.drawable.ic_person_add, "Username",
                    myUsername.isEmpty() ? "Tap to set" : "@" + myUsername + " · Tap to change");
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
        // Step 5: the header's pencil icon now opens the change-username
        // dialog (it was always drawn as an edit icon — ic_menu_edit — even
        // though it only copied to clipboard before). Long-press still copies.
        binding.btnCopyId.setOnClickListener(v -> showChangeUsernameDialog());
        binding.btnCopyId.setOnLongClickListener(v -> { copyUsername(); return true; });
        binding.tvUsername.setOnLongClickListener(v -> { copyUsername(); return true; });

        configureRow(binding.rowProfile.getRoot(), R.drawable.ic_person, "Edit Profile", "Name, photo, about");
        binding.rowProfile.getRoot().setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));

        configureRow(binding.rowAccountCenter.getRoot(), R.drawable.ic_person_add,
            "Account Center", "Add, switch, or remove accounts on this device");
        binding.rowAccountCenter.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, AccountCenterActivity.class)));

        configureRow(binding.rowUsername.getRoot(), R.drawable.ic_person_add, "Username",
            myUsername.isEmpty() ? "Tap to set" : "@" + myUsername + " · Tap to change");
        // Step 5: tap now opens the editable-username dialog (cooldown +
        // availability check) instead of just copying. Long-press to copy.
        binding.rowUsername.getRoot().setOnClickListener(v -> showChangeUsernameDialog());
        binding.rowUsername.getRoot().setOnLongClickListener(v -> { copyUsername(); return true; });

        configureRow(binding.rowCreatorDashboard.getRoot(), R.drawable.ic_group,
            "Creator Dashboard", "Analytics, earnings, and insights");
        binding.rowCreatorDashboard.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, ReelCreatorDashboardActivity.class)));

        configureRow(binding.rowMonetization.getRoot(), R.drawable.ic_star_outline,
            "Monetization", "Manage your earning settings");
        binding.rowMonetization.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, ReelMonetizationActivity.class)));

        configureRow(binding.rowMilestoneEarnings.getRoot(), R.drawable.ic_star_outline,
            "Milestone Earnings", "Like & follow to earn rewards");
        binding.rowMilestoneEarnings.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, MilestoneEarningsActivity.class)));

        configureRow(binding.rowVerifiedBadge.getRoot(), R.drawable.ic_verified_pink,
            "Get Verified Badge", "Stand out with a blue verified badge");
        binding.rowVerifiedBadge.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, VerifiedBadgeActivity.class)));

        configureRow(binding.rowStarTalent.getRoot(), R.drawable.ic_star_outline,
            "Apply for Star Talent", "Join our exclusive creator program");
        binding.rowStarTalent.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, StarTalentActivity.class)));

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

    private void copyUsername() {
        if (myUsername.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Username", "@" + myUsername));
        Toast.makeText(this, "Username copied!", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 5 — editable username (Settings), with a cooldown between changes
    // and the same O(1) usernames/{username} availability check + reserve
    // transaction pattern as the signup/migration flow in ProfileSetupActivity.
    // ─────────────────────────────────────────────────────────────────────

    private long usernameCooldownRemainingMs() {
        if (myUsernameChangedAt <= 0) return 0;
        long elapsed = System.currentTimeMillis() - myUsernameChangedAt;
        long remaining = Constants.USERNAME_CHANGE_COOLDOWN_MS - elapsed;
        return Math.max(0, remaining);
    }

    private void showChangeUsernameDialog() {
        if (myUid.isEmpty()) return;

        long cooldownLeft = usernameCooldownRemainingMs();
        if (cooldownLeft > 0) {
            long daysLeft = (long) Math.ceil(cooldownLeft / (24.0 * 60 * 60 * 1000));
            new MaterialAlertDialogBuilder(this)
                .setTitle("Username abhi nahi badal sakte")
                .setMessage("Aapne recently username change kiya tha. " + daysLeft +
                    (daysLeft == 1 ? " din" : " din") + " baad phir try karo.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText etUsername = new android.widget.EditText(this);
        etUsername.setHint("username");
        etUsername.setText(myUsername);
        if (!myUsername.isEmpty()) etUsername.setSelection(myUsername.length());
        layout.addView(etUsername);

        final TextView tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, pad / 2, 0, 0);
        tvStatus.setText("Naya @handle chuno — dusre isse aapko dhoondh sakenge");
        tvStatus.setTextColor(getColor(R.color.text_secondary));
        layout.addView(tvStatus);

        // Reset per-dialog check state — this dialog's typing drives its own
        // availability check, independent of any earlier signup-flow state.
        lastCheckedUsername = null;
        usernameAvailable = false;

        etUsername.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String raw = s.toString();
                String normalized = raw.toLowerCase(java.util.Locale.getDefault())
                        .replaceAll("[^a-z0-9_]", "");
                if (!normalized.equals(raw)) {
                    s.replace(0, s.length(), normalized);
                    return; // afterTextChanged re-fires from this replace
                }
                scheduleUsernameCheck(normalized, tvStatus);
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("Change username")
            .setView(layout)
            .setPositiveButton("Save", null) // wired below so it doesn't auto-dismiss
            .setNegativeButton("Cancel", null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newUsername = etUsername.getText().toString().trim();
                if (newUsername.equals(myUsername)) { dialog.dismiss(); return; }
                if (newUsername.length() < 3) {
                    tvStatus.setTextColor(getColor(R.color.action_danger));
                    tvStatus.setText("Kam se kam 3 characters (a-z, 0-9, _)");
                    return;
                }
                if (!newUsername.equals(lastCheckedUsername) || !usernameAvailable) {
                    tvStatus.setTextColor(getColor(R.color.action_danger));
                    tvStatus.setText("Pehle availability check hone do");
                    return;
                }
                reserveAndSaveNewUsername(newUsername, dialog);
            });
        });

        dialog.show();
    }

    /** Same O(1) usernames/{username} lookup as ProfileSetupActivity. */
    private void scheduleUsernameCheck(String username, TextView tvStatus) {
        usernameAvailable = false;
        if (pendingUsernameCheck != null) usernameCheckHandler.removeCallbacks(pendingUsernameCheck);

        if (username.equals(myUsername)) {
            lastCheckedUsername = username;
            usernameAvailable = true;
            tvStatus.setTextColor(getColor(R.color.text_secondary));
            tvStatus.setText("Yeh aapka current username hai");
            return;
        }
        if (username.length() < 3) {
            tvStatus.setTextColor(getColor(R.color.text_secondary));
            tvStatus.setText("Kam se kam 3 characters (a-z, 0-9, _)");
            return;
        }

        tvStatus.setTextColor(getColor(R.color.text_secondary));
        tvStatus.setText("Check kar rahe hain...");

        final long myToken = ++usernameCheckToken;
        pendingUsernameCheck = () -> checkUsernameAvailability(username, myToken, tvStatus);
        usernameCheckHandler.postDelayed(pendingUsernameCheck, 400);
    }

    private void checkUsernameAvailability(String username, long token, TextView tvStatus) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("usernames").child(username)
            .get()
            .addOnSuccessListener(snap -> {
                if (token != usernameCheckToken) return; // stale — user kept typing
                lastCheckedUsername = username;
                boolean takenByOther = snap.exists() && !myUid.equals(snap.getValue(String.class));
                usernameAvailable = !takenByOther;
                if (takenByOther) {
                    tvStatus.setTextColor(getColor(R.color.action_danger));
                    tvStatus.setText("Yeh username already liya hua hai");
                } else {
                    tvStatus.setTextColor(getColor(R.color.status_online));
                    tvStatus.setText("Available ✓");
                }
            })
            .addOnFailureListener(e -> {
                if (token != usernameCheckToken) return;
                tvStatus.setTextColor(getColor(R.color.action_danger));
                tvStatus.setText("Check nahi ho paya, phir try karo");
            });
    }

    /** Reserves the new handle via the same collision-safe transaction used
     *  at signup, then releases the old one and updates users/{uid} +
     *  usernameChangedAt (starts the next cooldown window) + the Room cache. */
    private void reserveAndSaveNewUsername(String newUsername, AlertDialog dialog) {
        String oldUsername = myUsername;
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("usernames").child(newUsername)
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        com.google.firebase.database.MutableData data) {
                    Object current = data.getValue();
                    if (current != null && !current.equals(myUid)) {
                        return com.google.firebase.database.Transaction.abort();
                    }
                    data.setValue(myUid);
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override
                public void onComplete(com.google.firebase.database.DatabaseError error,
                        boolean committed, com.google.firebase.database.DataSnapshot snap) {
                    if (!committed) {
                        Toast.makeText(AccountMenuActivity.this,
                            "Yeh username abhi kisi aur ne le liya, dusra try karo",
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Old handle freed only after the new one is safely
                    // reserved — never leaves a window with zero reserved
                    // usernames for this account.
                    if (!oldUsername.isEmpty() && !oldUsername.equals(newUsername)) {
                        FirebaseDatabase.getInstance(Constants.DB_URL)
                            .getReference("usernames").child(oldUsername).removeValue();
                        // Record the change so FormerUsernamesActivity's count
                        // (currently always 0 — this node was never written to)
                        // reflects reality. Child key = old handle, mirroring
                        // the usernames/{username} reservation pattern.
                        FirebaseDatabase.getInstance(Constants.DB_URL)
                            .getReference("reelUsernameHistory").child(myUid).child(oldUsername)
                            .setValue(ServerValue.TIMESTAMP);
                    }
                    Map<String, Object> updates = new java.util.HashMap<>();
                    updates.put("username", newUsername);
                    updates.put("usernameChangedAt", ServerValue.TIMESTAMP);
                    FirebaseUtils.getUserRef(myUid).updateChildren(updates);

                    java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                        com.callx.app.db.AppDatabase db =
                            com.callx.app.db.AppDatabase.getInstance(getApplicationContext());
                        db.userDao().updateUsername(myUid, newUsername);
                    });

                    myUsername = newUsername;
                    myUsernameChangedAt = System.currentTimeMillis();
                    binding.tvUsername.setText("@" + newUsername);
                    configureRow(binding.rowUsername.getRoot(), R.drawable.ic_person_add, "Username",
                        "@" + newUsername + " · Tap to change");
                    Toast.makeText(AccountMenuActivity.this, "Username update ho gaya", Toast.LENGTH_SHORT).show();
                    if (dialog.isShowing()) dialog.dismiss();
                }
            });
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
                    com.callx.app.cache.LastMessagesCache.getInstance().clear();
                    com.callx.app.cache.LastMessagesDiskCache.clearForAccountAsync(this, myUid);
                    // v300 ultra: same reason as the chat snapshot clear above —
                    // Home's in-memory instant-paint mirror is per-process, not
                    // per-account, so it must be wiped explicitly on logout too.
                    com.callx.app.feed.HomeFragment.clearInMemoryFeedCache();
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

        // Step 5 fix: release the reserved handle too — otherwise it stays
        // permanently squatted (usernames/{username} → uid) even after the
        // account itself is gone, and nobody can ever claim it again.
        // Security rule only allows this because data.val() === auth.uid
        // still holds at this point (auth isn't cleared until below).
        if (!myUsername.isEmpty()) {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("usernames").child(myUsername).removeValue();
        }

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
                 com.callx.app.cache.LastMessagesCache.getInstance().clear();
                 com.callx.app.cache.LastMessagesDiskCache.clearForAccountAsync(this, uid);
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
