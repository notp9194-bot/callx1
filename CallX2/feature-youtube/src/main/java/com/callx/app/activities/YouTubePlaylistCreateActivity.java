package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/**
 * YouTubePlaylistCreateActivity
 * Nayi playlist banana — title, description, visibility (Public/Unlisted/Private)
 * Optional: ek video ID pass karo aur woh auto-add ho jaayega nayi playlist mein
 */
public class YouTubePlaylistCreateActivity extends AppCompatActivity {

    private EditText etTitle, etDescription;
    private RadioGroup rgVisibility;
    private Button btnCreate;
    private String preAddVideoId;
    private String myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlist_create);

        preAddVideoId = getIntent().getStringExtra("video_id");

        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }
        myUid = user.getUid();

        View btnBack = findViewById(R.id.btn_yt_plcreate_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        etTitle       = findViewById(R.id.et_yt_playlist_title);
        etDescription = findViewById(R.id.et_yt_playlist_desc);
        rgVisibility  = findViewById(R.id.rg_yt_playlist_visibility);
        btnCreate     = findViewById(R.id.btn_yt_create_playlist);

        btnCreate.setOnClickListener(v -> createPlaylist());
    }

    private void createPlaylist() {
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            etTitle.setError("Title daalo");
            return;
        }

        String description = etDescription.getText() != null
            ? etDescription.getText().toString().trim() : "";

        String visibility = "public";
        int checkedId = rgVisibility.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_yt_unlisted) visibility = "unlisted";
        else if (checkedId == R.id.rb_yt_private) visibility = "private";

        btnCreate.setEnabled(false);
        btnCreate.setText("Bana raha hai...");

        DatabaseReference db = FirebaseDatabase.getInstance(YouTubeFirebaseUtils.DB_URL)
            .getReference();

        String playlistId = db.child("youtube/playlists").child(myUid).push().getKey();
        if (playlistId == null) {
            Toast.makeText(this, "Error, dobara try karo", Toast.LENGTH_SHORT).show();
            btnCreate.setEnabled(true);
            btnCreate.setText("Playlist Banao");
            return;
        }

        YouTubePlaylist playlist = new YouTubePlaylist();
        playlist.playlistId   = playlistId;
        playlist.ownerUid     = myUid;
        playlist.title        = title;
        playlist.description  = description;
        playlist.visibility   = visibility;
        playlist.createdAt    = System.currentTimeMillis();
        playlist.videoCount   = 0;

        db.child("youtube/playlists").child(myUid).child(playlistId)
            .setValue(playlist)
            .addOnSuccessListener(unused -> {
                // Agar video ID pass hua hai to playlist mein add karo
                if (preAddVideoId != null && !preAddVideoId.isEmpty()) {
                    db.child("youtube/playlist_videos").child(playlistId)
                        .child(preAddVideoId).setValue(System.currentTimeMillis());
                    db.child("youtube/playlists").child(myUid).child(playlistId)
                        .child("videoCount").setValue(1);
                    Toast.makeText(this,
                        "\"" + title + "\" playlist bani aur video add hua!",
                        Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                        "\"" + title + "\" playlist ban gayi!",
                        Toast.LENGTH_SHORT).show();
                }
                setResult(RESULT_OK, new Intent()
                    .putExtra("playlist_id", playlistId)
                    .putExtra("playlist_title", title));
                finish();
            })
            .addOnFailureListener(e -> {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                btnCreate.setEnabled(true);
                btnCreate.setText("Playlist Banao");
            });
    }
}
