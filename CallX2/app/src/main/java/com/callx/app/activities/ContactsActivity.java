package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.R;

/**
 * ContactsActivity — used as a forward-message target.
 * Receives extras: forwardText, forwardType, forwardMedia.
 * Stub: shows a toast. Replace with full contacts UI when ready.
 */
public class ContactsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String forwardText  = intent != null ? intent.getStringExtra("forwardText")  : null;
        String forwardType  = intent != null ? intent.getStringExtra("forwardType")  : null;
        String forwardMedia = intent != null ? intent.getStringExtra("forwardMedia") : null;

        // TODO: Show contacts list to pick a forward target.
        // For now, show a toast and close.
        Toast.makeText(this,
                "Forward: " + (forwardText != null ? forwardText : forwardType),
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
