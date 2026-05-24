package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;

/** Create a new playlist with title, description, visibility. */
public class YouTubeCreatePlaylistActivity extends AppCompatActivity {

    private EditText  etTitle, etDescription;
    private RadioGroup rgVisibility;
    private String    myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_create_playlist);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_create_pl_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        etTitle       = findViewById(R.id.et_yt_pl_title);
        etDescription = findViewById(R.id.et_yt_pl_desc);
        rgVisibility  = findViewById(R.id.rg_yt_pl_visibility);

        View btnCreate = findViewById(R.id.btn_yt_create_pl_confirm);
        if (btnCreate != null) btnCreate.setOnClickListener(v -> createPlaylist());
    }

    private void createPlaylist() {
        if (myUid.isEmpty()) return;
        String title = etTitle != null ? etTitle.getText().toString().trim() : "";
        String desc  = etDescription != null ? etDescription.getText().toString().trim() : "";
        if (title.isEmpty()) {
            Toast.makeText(this, "Enter a playlist title", Toast.LENGTH_SHORT).show(); return;
        }
        String visibility = "public";
        if (rgVisibility != null) {
            int checked = rgVisibility.getCheckedRadioButtonId();
            if (checked == R.id.rb_yt_pl_unlisted) visibility = "unlisted";
            else if (checked == R.id.rb_yt_pl_private) visibility = "private";
        }
        DatabaseReference ref  = YouTubeFirebaseUtils.playlistsRef(myUid);
        String            plId = ref.push().getKey();
        if (plId == null) return;
        YouTubePlaylist pl = new YouTubePlaylist(plId, myUid, title, desc, visibility);
        ref.child(plId).setValue(pl).addOnSuccessListener(x -> {
            Toast.makeText(this, "Playlist created!", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
