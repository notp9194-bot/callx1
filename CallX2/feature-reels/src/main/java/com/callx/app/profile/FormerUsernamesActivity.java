package com.callx.app.profile;

import android.os.Bundle;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.google.firebase.database.*;

/**
 * FormerUsernamesActivity — shows how many times the account changed username.
 * Mirrors Instagram's "Former usernames" screen.
 */
public class FormerUsernamesActivity extends AppCompatActivity {

    public static final String EXTRA_UID   = "uid";
    public static final String EXTRA_NAME  = "name";
    public static final String EXTRA_COUNT = "count";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_former_usernames);

        String targetUid  = getIntent().getStringExtra(EXTRA_UID);
        String targetName = getIntent().getStringExtra(EXTRA_NAME);
        int    count      = getIntent().getIntExtra(EXTRA_COUNT, 0);

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        TextView tvBody = findViewById(R.id.tv_former_body);
        if (tvBody != null) {
            String name = targetName != null ? targetName : "This account";
            String body = count > 0
                ? name + " has changed their username " + count + " time" + (count == 1 ? "." : "s.")
                : name + " has never changed their username.";
            tvBody.setText(body);
        }
    }
}
