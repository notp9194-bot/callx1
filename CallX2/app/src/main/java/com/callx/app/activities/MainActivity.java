package com.callx.app.activities;
import android.Manifest;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.callx.app.R;
import com.callx.app.adapters.ViewPagerAdapter;
import com.callx.app.databinding.ActivityMainBinding;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import com.google.firebase.messaging.FirebaseMessaging;
public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private String myCallxId = "";
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
        // Overlay permission — background se popup launch karne ke liye Android 10+
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
                        android.provider.Settings
                            .ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }
        setSupportActionBar(binding.toolbar);
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
            if (id == R.id.nav_chats)  binding.viewPager.setCurrentItem(0);
            else if (id == R.id.nav_status) binding.viewPager.setCurrentItem(1);
            else if (id == R.id.nav_groups) binding.viewPager.setCurrentItem(2);
            else if (id == R.id.nav_calls)  binding.viewPager.setCurrentItem(3);
            return true;
        });
        binding.fabAction.setOnClickListener(v -> {
            int pos = binding.viewPager.getCurrentItem();
            if (pos == 0) startActivity(new Intent(this, SearchActivity.class));
            else if (pos == 1) startActivity(new Intent(this, NewStatusActivity.class));
            else if (pos == 2) startActivity(new Intent(this, NewGroupActivity.class));
            else startActivity(new Intent(this, SearchActivity.class));
        });
        binding.btnCopyId.setOnClickListener(v -> {
            if (myCallxId.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CallX ID", myCallxId));
            Toast.makeText(this, "ID copy ho gayi", Toast.LENGTH_SHORT).show();
        });
        loadMyId();
        refreshFcmToken();
        // Global request listener ab CallxApp me hai — har activity me kaam karta hai
    }
    private void updateFab(int position) {
        switch (position) {
            case 0: binding.fabAction.setImageResource(R.drawable.ic_status_add); break;
            case 1: binding.fabAction.setImageResource(R.drawable.ic_camera);     break;
            case 2: binding.fabAction.setImageResource(R.drawable.ic_group);      break;
            case 3: binding.fabAction.setImageResource(R.drawable.ic_phone);      break;
        }
    }
    private void loadMyId() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseDatabase.getInstance(Constants.DB_URL).getReference("users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                public void onDataChange(DataSnapshot snap) {
                    String id = snap.child("callxId").getValue(String.class);
                    if (id != null) {
                        myCallxId = id;
                        binding.tvMyId.setText("Mera CallX ID: " + id);
                    }
                }
                public void onCancelled(DatabaseError e) {}
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
        int id = item.getItemId();
        if (id == R.id.action_search) {
            startActivity(new Intent(this, SearchActivity.class)); return true;
        }
        if (id == R.id.action_profile) {
            startActivity(new Intent(this, ProfileActivity.class)); return true;
        }
        if (id == R.id.action_logout) {
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(this, AuthActivity.class));
            finish(); return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
