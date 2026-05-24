package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeCommentAdapter;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live Streaming Activity:
 * - Host mode: set stream title, start/stop live stream
 * - Viewer mode: watch live stream with live chat
 * - Live viewer count
 * - Live chat send + receive
 * - Fan-out notification to subscribers when going live
 */
public class YouTubeLiveActivity extends AppCompatActivity {

    private String      myUid, myName, myPhoto, streamId;
    private boolean     isHost    = false;
    private boolean     isLive    = false;

    private ExoPlayer   player;
    private PlayerView  playerView;
    private TextView    tvTitle, tvViewerCount, tvStatus;
    private RecyclerView rvChat;
    private EditText    etChat;
    private View        btnStart, btnStop, layoutHostSetup;
    private EditText    etStreamTitle;

    private ValueEventListener chatListener, viewerListener;
    private List<Map<String, Object>> chatMessages = new ArrayList<>();
    private ChatAdapter chatAdapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_live);

        myUid  = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        myName = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "User";
        myPhoto = FirebaseAuth.getInstance().getCurrentUser() != null &&
            FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl().toString() : null;

        streamId = getIntent().getStringExtra("stream_id");
        isHost   = getIntent().getBooleanExtra("is_host", false);

        View btnBack = findViewById(R.id.btn_yt_live_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> { leaveStream(); finish(); });

        playerView      = findViewById(R.id.pv_yt_live);
        tvTitle         = findViewById(R.id.tv_yt_live_title);
        tvViewerCount   = findViewById(R.id.tv_yt_live_viewers);
        tvStatus        = findViewById(R.id.tv_yt_live_status);
        rvChat          = findViewById(R.id.rv_yt_live_chat);
        etChat          = findViewById(R.id.et_yt_live_chat);
        btnStart        = findViewById(R.id.btn_yt_live_start);
        btnStop         = findViewById(R.id.btn_yt_live_stop);
        layoutHostSetup = findViewById(R.id.layout_yt_live_host_setup);
        etStreamTitle   = findViewById(R.id.et_yt_live_stream_title);

        chatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

        ImageButton btnSend = findViewById(R.id.btn_yt_live_send_chat);
        if (btnSend != null) btnSend.setOnClickListener(v -> sendChat());

        if (isHost) {
            if (layoutHostSetup != null) layoutHostSetup.setVisibility(View.VISIBLE);
            if (btnStart != null) btnStart.setOnClickListener(v -> startLiveStream());
            if (btnStop  != null) btnStop.setOnClickListener(v  -> stopLiveStream());
        } else {
            if (layoutHostSetup != null) layoutHostSetup.setVisibility(View.GONE);
            if (streamId != null) joinStream();
        }
    }

    private void startLiveStream() {
        String title = etStreamTitle != null
            ? etStreamTitle.getText().toString().trim() : "Live Stream";
        if (title.isEmpty()) title = "Live Stream";

        streamId = YouTubeFirebaseUtils.liveStreamsRef().push().getKey();
        if (streamId == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("streamId",    streamId);
        data.put("hostUid",     myUid);
        data.put("hostName",    myName);
        data.put("title",       title);
        data.put("isLive",      true);
        data.put("viewerCount", 0);
        data.put("startedAt",   System.currentTimeMillis());
        data.put("liveStreamUrl", "rtmp://live.callx.app/live/" + streamId);

        YouTubeFirebaseUtils.liveStreamRef(streamId).setValue(data);
        isLive = true;
        if (tvStatus != null) tvStatus.setText("🔴 LIVE");
        if (btnStart != null) btnStart.setVisibility(View.GONE);
        if (btnStop  != null) btnStop.setVisibility(View.VISIBLE);

        notifySubscribersGoingLive(title);
        listenChat();
        listenViewers();
    }

    private void stopLiveStream() {
        if (streamId == null) return;
        YouTubeFirebaseUtils.liveStreamRef(streamId).child("isLive").setValue(false);
        YouTubeFirebaseUtils.liveViewersRef(streamId).child(myUid).removeValue();
        isLive = false;
        if (tvStatus != null) tvStatus.setText("Ended");
        if (btnStop  != null) btnStop.setVisibility(View.GONE);
        if (btnStart != null) btnStart.setVisibility(View.VISIBLE);
        finish();
    }

    private void joinStream() {
        if (streamId == null) return;
        YouTubeFirebaseUtils.liveViewersRef(streamId).child(myUid).setValue(true);
        YouTubeFirebaseUtils.liveStreamRef(streamId).child("viewerCount")
            .setValue(ServerValue.increment(1));
        YouTubeFirebaseUtils.liveStreamRef(streamId).child("liveStreamUrl")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String url = snap.getValue(String.class);
                    if (url != null) initPlayer(url);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
        listenChat();
        listenViewers();
    }

    private void leaveStream() {
        if (streamId == null || myUid.isEmpty()) return;
        YouTubeFirebaseUtils.liveViewersRef(streamId).child(myUid).removeValue();
        YouTubeFirebaseUtils.liveStreamRef(streamId).child("viewerCount")
            .setValue(ServerValue.increment(-1));
    }

    private void initPlayer(String url) {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    private void sendChat() {
        if (etChat == null || myUid.isEmpty()) return;
        String text = etChat.getText().toString().trim();
        if (text.isEmpty()) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("uid",       myUid);
        msg.put("name",      myName);
        msg.put("photo",     myPhoto);
        msg.put("text",      text);
        msg.put("timestamp", System.currentTimeMillis());
        YouTubeFirebaseUtils.liveChatRef(streamId).push().setValue(msg);
        etChat.setText("");
    }

    private void listenChat() {
        chatListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                chatMessages.clear();
                for (DataSnapshot ds : snap.getChildren()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) ds.getValue();
                    if (m != null) chatMessages.add(m);
                }
                chatAdapter.notifyDataSetChanged();
                rvChat.scrollToPosition(chatMessages.size() - 1);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.liveChatRef(streamId)
            .orderByChild("timestamp").limitToLast(100)
            .addValueEventListener(chatListener);
    }

    private void listenViewers() {
        viewerListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long cnt = snap.getChildrenCount();
                if (tvViewerCount != null) tvViewerCount.setText(cnt + " watching");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.liveViewersRef(streamId).addValueEventListener(viewerListener);
    }

    private void notifySubscribersGoingLive(String title) {
        YouTubeFirebaseUtils.subscribersRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {
                        String subUid = ds.getKey();
                        if (subUid == null) continue;
                        String notifId =
                            YouTubeFirebaseUtils.notificationsRef(subUid).push().getKey();
                        if (notifId == null) continue;
                        YouTubeNotification n = new YouTubeNotification(
                            notifId, subUid, myUid, myName, myPhoto,
                            "live", streamId, title, null);
                        YouTubeFirebaseUtils.notificationsRef(subUid).child(notifId).setValue(n);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!isHost) leaveStream();
        if (player != null) { player.release(); player = null; }
        if (chatListener   != null && streamId != null)
            YouTubeFirebaseUtils.liveChatRef(streamId).removeEventListener(chatListener);
        if (viewerListener != null && streamId != null)
            YouTubeFirebaseUtils.liveViewersRef(streamId).removeEventListener(viewerListener);
    }

    // ── Chat adapter (inline simple) ─────────────────────────────────────────
    class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.CVH> {
        @NonNull @Override
        public CVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(YouTubeLiveActivity.this);
            tv.setPadding(24, 8, 24, 8);
            return new CVH(tv);
        }
        @Override public void onBindViewHolder(@NonNull CVH h, int pos) {
            Map<String, Object> msg = chatMessages.get(pos);
            String name = (String) msg.get("name");
            String text = (String) msg.get("text");
            ((TextView) h.itemView).setText((name != null ? name : "?") + ": " + text);
        }
        @Override public int getItemCount() { return chatMessages.size(); }
        class CVH extends RecyclerView.ViewHolder { CVH(View v) { super(v); } }
    }
}
