package com.callx.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

public class XSettingsAccountActivity extends AppCompatActivity {

    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_account);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_x_account);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Your account");
        }

        // Load user data and populate fields
        if (!myUid.isEmpty()) loadAccountInfo();

        // Username row — open edit profile
        View rowUsername = findViewById(R.id.row_x_username);
        if (rowUsername != null)
            rowUsername.setOnClickListener(v -> startActivity(
                new Intent(this, XEditProfileActivity.class)));

        // Phone row
        View rowPhone = findViewById(R.id.row_x_phone);
        if (rowPhone != null)
            rowPhone.setOnClickListener(v ->
                android.widget.Toast.makeText(this, "Phone settings coming soon", android.widget.Toast.LENGTH_SHORT).show());

        // Email row
        View rowEmail = findViewById(R.id.row_x_email);
        if (rowEmail != null)
            rowEmail.setOnClickListener(v ->
                android.widget.Toast.makeText(this, "Email settings coming soon", android.widget.Toast.LENGTH_SHORT).show());

        // Deactivate
        TextView tvDeactivate = findViewById(R.id.tv_x_deactivate);
        if (tvDeactivate != null)
            tvDeactivate.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Deactivate account?")
                .setMessage("This will deactivate your account. Your posts will not be deleted but your profile will be hidden.")
                .setPositiveButton("Deactivate", (dlg, w) ->
                    android.widget.Toast.makeText(this, "Account deactivation requires contacting support", android.widget.Toast.LENGTH_LONG).show())
                .setNegativeButton("Cancel", null)
                .show());
    }

    private void loadAccountInfo() {
        XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String handle   = snap.child("handle").getValue(String.class);
                String email    = snap.child("email").getValue(String.class);
                String phone    = snap.child("phone").getValue(String.class);
                boolean verified= Boolean.TRUE.equals(snap.child("verified").getValue(Boolean.class))
                               || Boolean.TRUE.equals(snap.child("blueVerified").getValue(Boolean.class));

                TextView tvHandle   = findViewById(R.id.tv_x_username_val);
                TextView tvEmail    = findViewById(R.id.tv_x_email_val);
                TextView tvPhone    = findViewById(R.id.tv_x_phone_val);
                TextView tvVerified = findViewById(R.id.tv_x_verified_val);

                if (tvHandle   != null && handle != null)  tvHandle.setText("@" + handle);
                if (tvEmail    != null && email  != null && !email.isEmpty())
                    tvEmail.setText(email);
                if (tvPhone    != null && phone  != null && !phone.isEmpty())
                    tvPhone.setText(phone);
                if (tvVerified != null) tvVerified.setText(verified ? "Verified" : "Not verified");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
