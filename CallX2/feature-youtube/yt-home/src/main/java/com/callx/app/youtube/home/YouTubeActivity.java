package com.callx.app.youtube.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.callx.app.youtube.core.navigator.YTNavigatorProvider;
import com.callx.app.youtube.core.utils.YTConstants;
import com.callx.app.youtube.home.R;
import com.callx.app.youtube.shorts.YouTubeShortsFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * YouTubeActivity — Main shell with bottom navigation.
 *
 * Tabs:
 *   Home          → YouTubeHomeFragment         (this module)
 *   Explore       → YouTubeExploreFragment       (this module)
 *   Shorts        → YouTubeShortsFragment        (:yt-shorts)
 *   Subscriptions → YouTubeSubscriptionsFragment (this module)
 *   Library       → YTNavigatorProvider.openLibrary() → YouTubeLibraryActivity (:yt-library)
 *
 * WHY no direct import of YouTubeLibraryFragment:
 *   yt-library depends on yt-core (for YouTubeVideoAdapter).
 *   yt-home depends on yt-library → yt-home would create a cycle if yt-library
 *   ever needed anything from yt-home. Using the Navigator breaks the cycle.
 */
public class YouTubeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube);

        bottomNav = findViewById(R.id.yt_bottom_nav);
        bottomNav.setOnItemSelectedListener(this::onNavItemSelected);

        // Handle deep-link tab selection (e.g. from YTNavigatorProvider.openShorts())
        String openTab = getIntent().getStringExtra("open_tab");
        if ("shorts".equals(openTab)) {
            loadFragment(new YouTubeShortsFragment());
            bottomNav.setSelectedItemId(R.id.nav_yt_shorts);
        } else if ("library".equals(openTab)) {
            // Library tab → open as Activity (avoids circular dependency)
            YTNavigatorProvider.get().openLibrary(this);
        } else if (savedInstanceState == null) {
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
            // Open Library as a separate Activity — no fragment import needed
            YTNavigatorProvider.get().openLibrary(this);
            return false; // don't highlight tab; we're leaving the activity
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
