package com.callx.app.youtube.home;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.callx.app.youtube.core.utils.YTConstants;
import com.callx.app.youtube.home.R;
import com.callx.app.youtube.library.YouTubeLibraryFragment;
import com.callx.app.youtube.shorts.YouTubeShortsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * YouTubeActivity — Main shell with bottom navigation.
 *
 * Tabs:
 *   Home         → YouTubeHomeFragment         (:yt-home)
 *   Explore      → YouTubeExploreFragment       (:yt-home)
 *   Shorts       → YouTubeShortsFragment        (:yt-shorts)
 *   Subscriptions→ YouTubeSubscriptionsFragment (:yt-home)
 *   Library      → YouTubeLibraryFragment       (:yt-library)
 *
 * Cross-module actions (search, upload, player) are delegated
 * to YTNavigatorProvider.get().openXxx() — no direct class references.
 */
public class YouTubeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube);

        bottomNav = findViewById(R.id.yt_bottom_nav);
        bottomNav.setOnItemSelectedListener(this::onNavItemSelected);

        if (savedInstanceState == null) {
            loadFragment(new YouTubeHomeFragment());
        }
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_yt_home) {
            loadFragment(new YouTubeHomeFragment());
        } else if (id == R.id.nav_yt_explore) {
            loadFragment(new YouTubeExploreFragment());
        } else if (id == R.id.nav_yt_shorts) {
            loadFragment(new YouTubeShortsFragment());
        } else if (id == R.id.nav_yt_subscriptions) {
            loadFragment(new YouTubeSubscriptionsFragment());
        } else if (id == R.id.nav_yt_library) {
            loadFragment(new YouTubeLibraryFragment());
        }
        return true;
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.yt_fragment_container, fragment)
            .commit();
    }
}
