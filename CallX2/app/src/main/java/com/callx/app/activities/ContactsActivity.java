package com.callx.app.activities;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.R;

/**
 * ContactsActivity — shown when forwarding a message to a contact.
 *
 * Displays the user's contact list so they can pick a recipient.
 * Full implementation pending; currently shows a placeholder.
 */
public class ContactsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String forwardText  = getIntent().getStringExtra("forwardText");
        String forwardType  = getIntent().getStringExtra("forwardType");
        String forwardMedia = getIntent().getStringExtra("forwardMedia");

        Toast.makeText(this, "Select a contact to forward to", Toast.LENGTH_SHORT).show();
        finish();
    }
}
