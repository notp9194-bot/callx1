package com.callx.app.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.youtube.R;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubePurchasesActivity — Purchases and memberships.
 * Shows YouTube Premium status, channel memberships, Super Chats history.
 */
public class YouTubePurchasesActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_purchases);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_purchases);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Purchases & Memberships");
        }

        // Premium status card
        View cardPremium = findViewById(R.id.card_yt_premium);
        TextView tvPremiumStatus = findViewById(R.id.tv_yt_premium_status);
        if (tvPremiumStatus != null) tvPremiumStatus.setText("Free Plan");

        View btnUpgrade = findViewById(R.id.btn_yt_upgrade_premium);
        if (btnUpgrade != null) {
            btnUpgrade.setOnClickListener(v -> android.widget.Toast.makeText(this,
                "YouTube Premium upgrade available at youtube.com/premium",
                android.widget.Toast.LENGTH_LONG).show());
        }

        // Memberships section
        TextView tvMemberships = findViewById(R.id.tv_yt_no_memberships);
        if (tvMemberships != null) {
            tvMemberships.setText("Koi active membership nahi hai.\n\nChannels ki membership join karo " +
                "exclusive content, badges aur emojis ke liye.");
        }

        // Super Chat history
        TextView tvSuperChats = findViewById(R.id.tv_yt_no_superchats);
        if (tvSuperChats != null) {
            tvSuperChats.setText("Koi Super Chat transaction nahi hai.");
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
