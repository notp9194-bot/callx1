package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

/**
 * YouTubeFamilyCenterActivity — Family Center screen.
 * Explains family linking and parental controls.
 */
public class YouTubeFamilyCenterActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_family_center);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_family_center);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Family Center");
        }

        Button btnLink = findViewById(R.id.btn_yt_family_link);
        if (btnLink != null) {
            btnLink.setOnClickListener(v ->
                Toast.makeText(this,
                    "Family link karne ke liye families.google.com visit karein.",
                    Toast.LENGTH_LONG).show());
        }

        View btnKidsApp = findViewById(R.id.btn_yt_kids_app);
        if (btnKidsApp != null) {
            btnKidsApp.setOnClickListener(v ->
                Toast.makeText(this,
                    "YouTube Kids app install karo Play Store se — bacchon ke liye safe content.",
                    Toast.LENGTH_LONG).show());
        }

        View btnParentalControls = findViewById(R.id.btn_yt_parental_controls);
        if (btnParentalControls != null) {
            btnParentalControls.setOnClickListener(v ->
                Toast.makeText(this,
                    "Parental controls Google Family Link app se manage karein.",
                    Toast.LENGTH_LONG).show());
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
