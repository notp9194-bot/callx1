package com.callx.app.playlist;

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

/**
 * YouTubePlaylistCreateActivity — nayi playlist banana
 * Title, description, visibility (Public/Unlisted/Private)
 * Optional: video_id pass karo to auto-add
 */
public class YouTubePlaylistCreateActivity extends AppCompatActivity {

    private EditText etTitle, etDescription;
    private RadioGroup rgVisibility;
    private Button btnCreate;
    private String preAddVideoId, myUid;

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
        if (title.isEmpty()) { etTitle.setError("Title daalo"); return; }

        String desc = etDescription.getText() != null
            ? etDescription.getText().toString().trim() : "";

        String visibility = "public";
        int checkedId = rgVisibility.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_yt_unlisted)  visibility = "unlisted";
        else if (checkedId == R.id.rb_yt_private) visibility = "private";

        btnCreate.setEnabled(false);
        btnCreate.setText("Bana raha hai...");

        // Use YouTubeFirebaseUtils.playlistsRef — internally uses Constants.DB_URL
        DatabaseReference ref = YouTubeFirebaseUtils.playlistsRef(myUid).push();
        String playlistId = ref.getKey();
        if (playlistId == null) {
            Toast.makeText(this, "Error, dobara try karo", Toast.LENGTH_SHORT).show();
            btnCreate.setEnabled(true); btnCreate.setText("Playlist Banao"); return;
        }

        YouTubePlaylist playlist = new YouTubePlaylist();
        playlist.playlistId  = playlistId;
        playlist.ownerUid    = myUid;
        playlist.title       = title;
        playlist.description = desc;
        playlist.visibility  = visibility;
        playlist.createdAt   = System.currentTimeMillis();
        playlist.videoCount  = 0;

        ref.setValue(playlist)
            .addOnSuccessListener(unused -> {
                if (preAddVideoId != null && !preAddVideoId.isEmpty()) {
                    YouTubeFirebaseUtils.playlistVideosRef(myUid, playlistId)
                        .child(preAddVideoId).setValue(System.currentTimeMillis());
                    YouTubeFirebaseUtils.playlistRef(myUid, playlistId)
                        .child("videoCount").setValue(1);
                    Toast.makeText(this, "\"" + title + "\" bani aur video add hua!",
                        Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "\"" + title + "\" playlist ban gayi!",
                        Toast.LENGTH_SHORT).show();
                }
                setResult(RESULT_OK, new Intent()
                    .putExtra("playlist_id", playlistId)
                    .putExtra("playlist_title", title));
                finish();
            })
            .addOnFailureListener(e -> {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                btnCreate.setEnabled(true); btnCreate.setText("Playlist Banao");
            });
    }
}
