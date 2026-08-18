package com.callx.app.settings;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;
public class YouTubeLiveChatSettingsActivity extends AppCompatActivity {
    private static final String PREFS = "yt_livechat_prefs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_live_chat_settings);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Toolbar toolbar = findViewById(R.id.toolbar_yt_live_chat);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_live_chat);
        }
        SwitchCompat swLiveChat = findViewById(R.id.sw_yt_live_chat_enabled);
        if (swLiveChat != null) {
            swLiveChat.setChecked(prefs.getBoolean("live_chat_enabled", true));
            swLiveChat.setOnCheckedChangeListener((b,c) -> prefs.edit().putBoolean("live_chat_enabled",c).apply());
        }
        SwitchCompat swReplay = findViewById(R.id.sw_yt_live_chat_replay);
        if (swReplay != null) {
            swReplay.setChecked(prefs.getBoolean("live_chat_replay", true));
            swReplay.setOnCheckedChangeListener((b,c) -> prefs.edit().putBoolean("live_chat_replay",c).apply());
        }
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
