package com.callx.app.music;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.callx.app.reels.R;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

/**
 * SoundUploadActivity — Upload original audio tracks to the Reels music library.
 *
 * Storage: Cloudinary (resource_type=raw, folder=callx/sounds/{uid})
 * DB:      Firebase Realtime DB — musicLibrary/{soundId}
 */
public class SoundUploadActivity extends AppCompatActivity {

    public static final String RESULT_SOUND_ID    = "upload_sound_id";
    public static final String RESULT_SOUND_TITLE = "upload_sound_title";

    private static final int RC_PICK_AUDIO = 801;
    private static final int RC_READ_PERM  = 802;

    private static final String[] GENRES = {
        "Pop", "Hip-Hop", "Chill", "EDM", "Romantic", "Lo-Fi", "Dance",
        "R&B", "Classical", "Jazz", "Rock", "Folk", "Indie", "Other"
    };

    private ImageButton btnBack;
    private TextView    tvPickedFile;
    private Button      btnPickAudio, btnUpload;
    private EditText    etTitle, etArtist;
    private Spinner     spinnerGenre;
    private ProgressBar progressUpload;
    private TextView    tvUploadStatus;

    private Uri    pickedAudioUri;
    private long   durationMs = 0L;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_upload);
        myUid = FirebaseAuth.getInstance().getUid();
        bindViews();
    }

    private void bindViews() {
        btnBack        = findViewById(R.id.btn_upload_back);
        tvPickedFile   = findViewById(R.id.tv_upload_picked_file);
        btnPickAudio   = findViewById(R.id.btn_pick_audio);
        btnUpload      = findViewById(R.id.btn_do_upload);
        etTitle        = findViewById(R.id.et_upload_title);
        etArtist       = findViewById(R.id.et_upload_artist);
        spinnerGenre   = findViewById(R.id.spinner_upload_genre);
        progressUpload = findViewById(R.id.progress_upload);
        tvUploadStatus = findViewById(R.id.tv_upload_status);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (spinnerGenre != null) {
            ArrayAdapter<String> ga = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, GENRES);
            ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerGenre.setAdapter(ga);
        }

        if (btnPickAudio != null) btnPickAudio.setOnClickListener(v -> pickAudioFile());
        if (btnUpload    != null) btnUpload.setOnClickListener(v    -> startUpload());
    }

    // ─── Pick Audio ───────────────────────────────────────────────────────────

    private void pickAudioFile() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, RC_READ_PERM);
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Select audio"), RC_PICK_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == RC_READ_PERM && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            pickAudioFile();
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            pickedAudioUri = data.getData();
            if (pickedAudioUri != null) {
                String name = getFileName(pickedAudioUri);
                if (tvPickedFile != null) tvPickedFile.setText(name);
                // Pre-fill title from filename
                if (etTitle != null && etTitle.getText().toString().isEmpty()) {
                    String base = name.contains(".")
                        ? name.substring(0, name.lastIndexOf('.')) : name;
                    etTitle.setText(base);
                }
                // Read duration via MediaMetadataRetriever
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(this, pickedAudioUri);
                    String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    durationMs = d != null ? Long.parseLong(d) : 0L;
                    mmr.release();
                } catch (Exception ignored) {}
            }
        }
    }

    // ─── Upload ───────────────────────────────────────────────────────────────

    private void startUpload() {
        if (pickedAudioUri == null) {
            Toast.makeText(this, "Please pick an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        String title  = etTitle  != null ? etTitle.getText().toString().trim()  : "";
        String artist = etArtist != null ? etArtist.getText().toString().trim() : "";
        String genre  = (spinnerGenre != null && spinnerGenre.getSelectedItem() != null)
            ? spinnerGenre.getSelectedItem().toString() : "Other";

        if (title.isEmpty()) {
            Toast.makeText(this, "Title is required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (myUid == null) {
            Toast.makeText(this, "Not signed in", Toast.LENGTH_SHORT).show();
            return;
        }

        setUploading(true);

        // Cloudinary folder: callx/sounds/{uid}
        String folder = "callx/sounds/" + myUid;

        // resource_type = "raw" for audio files (not image/video)
        CloudinaryUploader.upload(this, pickedAudioUri, folder, "raw",
            new CloudinaryUploader.UploadCallback() {

                @Override
                public void onSuccess(CloudinaryUploader.Result result) {
                    if (progressUpload != null) progressUpload.setProgress(100);
                    if (tvUploadStatus != null) tvUploadStatus.setText("Saving to library…");
                    // Use duration from Cloudinary if available, otherwise from MMR
                    long finalDuration = (result.durationMs != null && result.durationMs > 0)
                        ? result.durationMs : durationMs;
                    saveToDatabase(title, artist, genre, result.secureUrl, finalDuration);
                }

                @Override
                public void onError(String message) {
                    setUploading(false);
                    Toast.makeText(SoundUploadActivity.this,
                        "Upload failed: " + message, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void saveToDatabase(String title, String artist, String genre,
                                String audioUrl, long finalDurationMs) {
        DatabaseReference lib   = FirebaseUtils.getMusicLibraryRef();
        DatabaseReference entry = lib.push();

        Map<String, Object> data = new HashMap<>();
        data.put("title",      title);
        data.put("artist",     artist.isEmpty() ? "Unknown Artist" : artist);
        data.put("genre",      genre);
        data.put("audioUrl",   audioUrl);
        data.put("durationMs", finalDurationMs);
        data.put("uploadedBy", myUid);
        data.put("addedAt",    System.currentTimeMillis());
        data.put("usageCount", 0L);
        data.put("isOriginal", true);

        entry.setValue(data)
            .addOnSuccessListener(v -> {
                setUploading(false);
                if (tvUploadStatus != null) tvUploadStatus.setText("Upload complete!");
                Toast.makeText(this,
                    "\"" + title + "\" uploaded to library", Toast.LENGTH_LONG).show();

                Intent result = new Intent();
                result.putExtra(RESULT_SOUND_ID,    entry.getKey());
                result.putExtra(RESULT_SOUND_TITLE, title);
                setResult(RESULT_OK, result);
                finish();
            })
            .addOnFailureListener(e -> {
                setUploading(false);
                Toast.makeText(this,
                    "Saved to storage but DB write failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            });
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private void setUploading(boolean uploading) {
        if (progressUpload  != null)
            progressUpload.setVisibility(uploading ? View.VISIBLE : View.GONE);
        if (btnUpload       != null) btnUpload.setEnabled(!uploading);
        if (btnPickAudio    != null) btnPickAudio.setEnabled(!uploading);
        if (tvUploadStatus  != null)
            tvUploadStatus.setVisibility(uploading ? View.VISIBLE : View.GONE);
    }

    private String getFileName(Uri uri) {
        String path = uri.getPath();
        if (path == null) return "audio_file";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
