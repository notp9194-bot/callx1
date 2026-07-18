package com.callx.app.profile;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * About this account — like Instagram's "About this account" screen.
 * Shows: profile photo, username, date joined, account based in, former usernames.
 *
 * Extras:
 *   EXTRA_UID   — target user UID
 *   EXTRA_NAME  — display name
 *   EXTRA_PHOTO — photo URL
 */
public class AboutAccountActivity extends AppCompatActivity {

    public static final String EXTRA_UID   = "uid";
    public static final String EXTRA_NAME  = "name";
    public static final String EXTRA_PHOTO = "photo";

    private String targetUid, targetName, targetPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_account);

        targetUid   = getIntent().getStringExtra(EXTRA_UID);
        targetName  = getIntent().getStringExtra(EXTRA_NAME);
        targetPhoto = getIntent().getStringExtra(EXTRA_PHOTO);

        if (targetUid == null) { finish(); return; }

        // Back button
        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Avatar
        CircleImageView ivAvatar = findViewById(R.id.iv_avatar);
        if (ivAvatar != null && targetPhoto != null && !targetPhoto.isEmpty()) {
            Glide.with(this).load(targetPhoto).circleCrop()
                .placeholder(R.drawable.ic_person).override(200, 200).into(ivAvatar);
        }

        // Username
        TextView tvUsername = findViewById(R.id.tv_username);
        if (tvUsername != null) tvUsername.setText(targetName != null ? targetName : "");

        // "See why this information is important" link
        TextView tvInfo = findViewById(R.id.tv_info);
        if (tvInfo != null) {
            String full = "To help keep our community authentic, we're showing information about profiles on this app. See why this information is important.";
            SpannableString ss = new SpannableString(full);
            int start = full.indexOf("See why");
            if (start >= 0) {
                ss.setSpan(new ForegroundColorSpan(0xFF6C5CE7), start, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new UnderlineSpan(), start, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tvInfo.setText(ss);
        }

        // Load from Firebase
        loadAccountInfo();
    }

    private void loadAccountInfo() {
        FirebaseUtils.getUserRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;

                    // Date joined — from createdAt timestamp
                    Long createdAt = snap.child("createdAt").getValue(Long.class);
                    TextView tvDateJoined = findViewById(R.id.tv_date_joined);
                    if (tvDateJoined != null) {
                        if (createdAt != null && createdAt > 0) {
                            Date d = new Date(createdAt);
                            SimpleDateFormat sdf = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
                            tvDateJoined.setText(sdf.format(d));
                        } else {
                            tvDateJoined.setText("Unknown");
                        }
                    }

                    // Account based in — country field
                    String country = snap.child("country").getValue(String.class);
                    if (country == null || country.isEmpty())
                        country = snap.child("location").getValue(String.class);
                    TextView tvCountry = findViewById(R.id.tv_country);
                    if (tvCountry != null)
                        tvCountry.setText((country != null && !country.isEmpty()) ? country : "Unknown");

                    // Former usernames count
                    loadFormerUsernames(snap);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadFormerUsernames(DataSnapshot userSnap) {
        // Try reelUsernameHistory/{uid} or usernameHistory child
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reelUsernameHistory").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    int count = (int) snap.getChildrenCount();

                    TextView tvFormerCount = findViewById(R.id.tv_former_usernames_count);
                    LinearLayout layoutFormerUsernames = findViewById(R.id.layout_former_usernames);

                    if (tvFormerCount != null) tvFormerCount.setText(String.valueOf(count));

                    if (layoutFormerUsernames != null) {
                        layoutFormerUsernames.setOnClickListener(v -> {
                            Intent i = new Intent(AboutAccountActivity.this, FormerUsernamesActivity.class);
                            i.putExtra(FormerUsernamesActivity.EXTRA_UID,   targetUid);
                            i.putExtra(FormerUsernamesActivity.EXTRA_NAME,  targetName);
                            i.putExtra(FormerUsernamesActivity.EXTRA_COUNT, count);
                            startActivity(i);
                        });
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
