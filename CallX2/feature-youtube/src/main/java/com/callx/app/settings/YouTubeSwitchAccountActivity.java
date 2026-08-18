package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.bumptech.glide.Glide;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * YouTubeSwitchAccountActivity — Account switcher.
 * Shows current account info and allows sign-out.
 */
public class YouTubeSwitchAccountActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_switch_account);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_switch_account);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Switch Account");
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        String myUid = user.getUid();

        CircleImageView ivAvatar = findViewById(R.id.iv_yt_account_avatar);
        TextView tvName  = findViewById(R.id.tv_yt_account_name);
        TextView tvEmail = findViewById(R.id.tv_yt_account_email);
        TextView tvHandle= findViewById(R.id.tv_yt_account_handle);

        // Set email from Firebase Auth
        if (tvEmail != null && user.getEmail() != null) {
            tvEmail.setText(user.getEmail());
        }

        // Load channel info from Firebase
        YouTubeFirebaseUtils.channelRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String name   = snap.child("channelName").getValue(String.class);
                    String photo  = snap.child("photoUrl").getValue(String.class);
                    String handle = snap.child("handle").getValue(String.class);

                    if (tvName   != null) tvName.setText(name != null ? name : "YouTube User");
                    if (tvHandle != null && handle != null && !handle.isEmpty())
                        tvHandle.setText("@" + handle.replace("@", ""));

                    if (ivAvatar != null && photo != null && !photo.isEmpty()) {
                        Glide.with(YouTubeSwitchAccountActivity.this)
                            .load(photo).circleCrop().override(120, 120)
                            .placeholder(R.drawable.ic_person).into(ivAvatar);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // Sign out button
        Button btnSignOut = findViewById(R.id.btn_yt_sign_out);
        if (btnSignOut != null) {
            btnSignOut.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Sign Out?")
                    .setMessage("YouTube se sign out karna chahte ho?")
                    .setPositiveButton("Sign Out", (dlg, w) -> {
                        FirebaseAuth.getInstance().signOut();
                        Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        // Add account placeholder
        Button btnAddAccount = findViewById(R.id.btn_yt_add_account);
        if (btnAddAccount != null) {
            btnAddAccount.setOnClickListener(v ->
                Toast.makeText(this,
                    "Multiple accounts feature coming soon!", Toast.LENGTH_SHORT).show());
        }

        // Manage account
        View btnManage = findViewById(R.id.btn_yt_manage_account);
        if (btnManage != null) {
            btnManage.setOnClickListener(v ->
                Toast.makeText(this,
                    "Manage your Google account at myaccount.google.com", Toast.LENGTH_LONG).show());
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
