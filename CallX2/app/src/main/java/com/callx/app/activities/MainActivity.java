package com.callx.app.activities;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.adapters.ViewPagerAdapter;
import com.callx.app.databinding.ActivityMainBinding;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish(); return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }

        // Full-screen intent permission — Android 14+
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }

        setSupportActionBar(binding.toolbar);

        // Search button in toolbar
        binding.btnSearchToolbar.setOnClickListener(v ->
            startActivity(new Intent(this, SearchActivity.class)));

        // Avatar click → account menu
        binding.ivAvatarMenu.setOnClickListener(v ->
            startActivity(new Intent(this, AccountMenuActivity.class)));

        binding.viewPager.setAdapter(new ViewPagerAdapter(this));
        binding.viewPager.setUserInputEnabled(true);
        binding.viewPager.registerOnPageChangeCallback(
            new ViewPager2.OnPageChangeCallback() {
                @Override public void onPageSelected(int position) {
                    switch (position) {
                        case 0: binding.bottomNav.setSelectedItemId(R.id.nav_chats);  break;
                        case 1: binding.bottomNav.setSelectedItemId(R.id.nav_status); break;
                        case 2: binding.bottomNav.setSelectedItemId(R.id.nav_groups); break;
                        case 3: binding.bottomNav.setSelectedItemId(R.id.nav_calls);  break;
                    }
                    updateFab(position);
                }
            });

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_chats)       binding.viewPager.setCurrentItem(0);
            else if (id == R.id.nav_status) binding.viewPager.setCurrentItem(1);
            else if (id == R.id.nav_groups) binding.viewPager.setCurrentItem(2);
            else if (id == R.id.nav_calls)  binding.viewPager.setCurrentItem(3);
            return true;
        });

        binding.fabAction.setOnClickListener(v -> {
            int pos = binding.viewPager.getCurrentItem();
            if      (pos == 0) startActivity(new Intent(this, SearchActivity.class));
            else if (pos == 1) startActivity(new Intent(this, NewStatusActivity.class));
            else if (pos == 2) startActivity(new Intent(this, NewGroupActivity.class));
            else               startActivity(new Intent(this, SearchActivity.class));
        });

        loadMyAvatar();
        refreshFcmToken();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMyAvatar(); // refresh avatar after profile edit
    }

    private void updateFab(int position) {
        switch (position) {
            case 0: binding.fabAction.setImageResource(R.drawable.ic_status_add); break;
            case 1: binding.fabAction.setImageResource(R.drawable.ic_camera);     break;
            case 2: binding.fabAction.setImageResource(R.drawable.ic_group);      break;
            case 3: binding.fabAction.setImageResource(R.drawable.ic_phone);      break;
        }
    }

    private void loadMyAvatar() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (uid == null) return;
        FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (photo != null && !photo.isEmpty()) {
                        Glide.with(MainActivity.this)
                            .load(photo)
                            .apply(RequestOptions.circleCropTransform())
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(binding.ivAvatarMenu);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void refreshFcmToken() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null) return;
                FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference("users").child(uid)
                    .child("fcmToken").setValue(token);
            });
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            startActivity(new Intent(this, SearchActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
