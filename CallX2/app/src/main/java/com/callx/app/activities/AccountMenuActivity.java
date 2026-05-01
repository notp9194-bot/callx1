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
import com.callx.app.R;
import com.callx.app.databinding.ActivityAccountMenuBinding;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

public class AccountMenuActivity extends AppCompatActivity {

    private ActivityAccountMenuBinding binding;
    private String myCallxId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccountMenuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        loadProfile();
        setupMenuRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfile();
    }

    private void loadProfile() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (uid == null) return;

        FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name  = orEmpty(snap.child("name").getValue(String.class));
                    String about = orEmpty(snap.child("about").getValue(String.class));
                    String photo = orEmpty(snap.child("photoUrl").getValue(String.class));
                    myCallxId   = orEmpty(snap.child("callxId").getValue(String.class));

                    binding.tvProfileName.setText(name.isEmpty() ? "User" : name);
                    binding.tvProfileAbout.setText(about.isEmpty()
                        ? "Hey there! I am using CallX" : about);
                    binding.tvCallxId.setText("ID: " + (myCallxId.isEmpty() ? "—" : myCallxId));

                    if (!photo.isEmpty()) {
                        Glide.with(AccountMenuActivity.this)
                            .load(photo).circleCrop()
                            .placeholder(R.drawable.ic_person)
                            .into(binding.ivProfileAvatar);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void setupMenuRows() {
        // Profile card edit button
        binding.btnEditProfile.setOnClickListener(v ->
            startActivity(new Intent(this, ProfileActivity.class)));
        binding.ivProfileAvatar.setOnClickListener(v ->
            startActivity(new Intent(this, ProfileActivity.class)));

        // Copy CallX ID
        binding.btnCopyId.setOnClickListener(v -> {
            if (myCallxId.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CallX ID", myCallxId));
            Toast.makeText(this, "CallX ID copied!", Toast.LENGTH_SHORT).show();
        });

        // Row: Edit Profile
        configureRow(binding.rowProfile, R.drawable.ic_person,
            "Edit Profile", "Name, photo, about");
        binding.rowProfile.setOnClickListener(v ->
            startActivity(new Intent(this, ProfileActivity.class)));

        // Row: CallX ID
        configureRow(binding.rowCallxId, R.drawable.ic_person_add,
            "My CallX ID", myCallxId.isEmpty() ? "Tap to copy" : myCallxId);
        binding.rowCallxId.setOnClickListener(v -> {
            if (myCallxId.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CallX ID", myCallxId));
            Toast.makeText(this, "CallX ID copied!", Toast.LENGTH_SHORT).show();
        });

        // Row: Privacy & Security
        configureRow(binding.rowPrivacy, R.drawable.ic_phone,
            "Privacy & Security", "App lock, fingerprint, PIN, pattern");
        binding.rowPrivacy.setOnClickListener(v ->
            startActivity(new Intent(this, PrivacySecurityActivity.class)));

        // Row: Notifications
        configureRow(binding.rowNotifications, R.drawable.ic_status_notification,
            "Notifications", "Message and call alerts");
        binding.rowNotifications.setOnClickListener(v ->
            Toast.makeText(this, "Notification settings — coming soon", Toast.LENGTH_SHORT).show());

        // Row: Chats
        configureRow(binding.rowChats, R.drawable.ic_message_notification,
            "Chats", "Chat history and media");
        binding.rowChats.setOnClickListener(v ->
            Toast.makeText(this, "Chat settings — coming soon", Toast.LENGTH_SHORT).show());

        // Row: Storage & Data
        configureRow(binding.rowStorage, R.drawable.ic_file,
            "Storage & Data", "Network usage, auto-download");
        binding.rowStorage.setOnClickListener(v ->
            Toast.makeText(this, "Storage settings — coming soon", Toast.LENGTH_SHORT).show());

        // Row: Help
        configureRow(binding.rowHelp, R.drawable.ic_search,
            "Help Center", "FAQ, contact support");
        binding.rowHelp.setOnClickListener(v ->
            Toast.makeText(this, "Help — coming soon", Toast.LENGTH_SHORT).show());

        // Row: About
        configureRow(binding.rowAbout, R.drawable.ic_group,
            "About CallX", "App version and licenses");
        binding.rowAbout.setOnClickListener(v -> showAboutDialog());

        // Row: Logout
        configureRow(binding.rowLogout, R.drawable.ic_logout,
            "Logout", null);
        binding.rowLogout.findViewById(R.id.tv_menu_title)
            .setTextColor(getColor(R.color.action_danger));
        binding.rowLogout.setOnClickListener(v -> confirmLogout());
    }

    private void configureRow(View row, int iconRes, String title, String subtitle) {
        row.findViewById(android.R.id.icon); // ensure view is inflated
        ((android.widget.ImageView) row.findViewById(R.id.iv_menu_icon))
            .setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.tv_menu_title)).setText(title);
        TextView sub = row.findViewById(R.id.tv_menu_subtitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            sub.setText(subtitle);
            sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Kya aap logout karna chahte hain?")
            .setPositiveButton("Logout", (d, w) -> {
                FirebaseAuth.getInstance().signOut();
                Intent i = new Intent(this, AuthActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
            .setTitle("About CallX")
            .setMessage("CallX v3.0.0\n\nProduction-grade messaging and video calling app.\n\nBuilt with Firebase + WebRTC")
            .setPositiveButton("OK", null)
            .show();
    }

    private String orEmpty(String s) { return s == null ? "" : s; }
}
