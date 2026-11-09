package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;

/**
 * v790 — Screen 4 "Request Permission". Sends a remixRequests/{owner}/{id} (status pending, expires in 48 h);
 * the owner gets a push (Cloud Function onRemixRequestCreated) and decides in the requests inbox.
 * Used for Collab (always) and for Remix when the creator set "approval required".
 */
public class RemixRequestActivity extends AppCompatActivity {

    private RemixCollabTarget target;
    private String mode = RemixCollabTarget.MODE_REMIX;
    private EditText etMessage;
    private Button btnSend;
    private boolean sending;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remix_request);
        target = RemixCollabTarget.from(getIntent());
        if (RemixCollabTarget.MODE_COLLAB.equals(getIntent().getStringExtra(RemixCollabTarget.X_MODE)))
            mode = RemixCollabTarget.MODE_COLLAB;
        final boolean collab = RemixCollabTarget.MODE_COLLAB.equals(mode);
        final String what = collab ? "collab" : "remix";

        if (!target.isValid()) { finish(); return; }

        ImageView iv = findViewById(R.id.iv_owner);
        if (!target.ownerPhoto.isEmpty()) Glide.with(this).load(target.ownerPhoto).circleCrop().into(iv);
        else if (!target.thumbUrl.isEmpty()) Glide.with(this).load(target.thumbUrl).circleCrop().into(iv);
        else iv.setImageResource(R.drawable.ic_person);

        TextView sub = findViewById(R.id.tv_sub);
        String who = target.ownerName.isEmpty() ? "The creator" : "@" + target.ownerName;
        sub.setText(who + " will be notified that you want to " + what + " this post.");

        etMessage = findViewById(R.id.et_message);
        etMessage.setText("Hi! I'd like to create a " + (collab ? "Collab" : "Remix")
                + " with your post. Hope you allow it! 🙏");
        final TextView count = findViewById(R.id.tv_count);
        count.setText(etMessage.length() + "/200");
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { count.setText(s.length() + "/200"); }
        });

        btnSend = findViewById(R.id.btn_send);
        btnSend.setOnClickListener(v -> send());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
    }

    private void send() {
        if (sending) return;
        sending = true;
        btnSend.setEnabled(false);
        RemixRequestHelper.send(target.ownerUid, target.reelId, target.thumbUrl, mode,
                etMessage.getText().toString().trim(),
                FirebaseUtils.getCurrentName(), FirebaseUtils.getCurrentPhotoUrl(),
                new RemixRequestHelper.SendCb() {
                    @Override public void onSent(@NonNull String requestId) {
                        if (isFinishing() || isDestroyed()) return;
                        Intent i = new Intent(RemixRequestActivity.this, RemixRequestStatusActivity.class);
                        target.put(i);
                        i.putExtra(RemixCollabTarget.X_MODE, mode);
                        i.putExtra(RemixCollabTarget.X_REQUEST_ID, requestId);
                        startActivity(i);
                        finish();
                    }
                    @Override public void onError(@NonNull String reason) {
                        if (isFinishing() || isDestroyed()) return;
                        sending = false;
                        btnSend.setEnabled(true);
                        Toast.makeText(RemixRequestActivity.this,
                                "own_post".equals(reason) ? "You can't send a request for your own post"
                                        : "Couldn't send the request. Please try again", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
