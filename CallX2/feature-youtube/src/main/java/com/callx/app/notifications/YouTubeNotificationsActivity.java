package com.callx.app.notifications;

import com.callx.app.player.YouTubePlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.notifications.YouTubeNotificationAdapter;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeNotificationsActivity extends AppCompatActivity {

    private RecyclerView                rvNotifs;
    private YouTubeNotificationAdapter  adapter;
    private String                      myUid;
    private ValueEventListener          notifListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_notifications);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_notif_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvNotifs = findViewById(R.id.rv_yt_notifications);
        adapter  = new YouTubeNotificationAdapter(this, new ArrayList<>(), notif -> {
            markRead(notif.notifId);
            if (notif.videoId != null)
                startActivity(new Intent(this, YouTubePlayerActivity.class)
                    .putExtra("video_id", notif.videoId));
        });
        rvNotifs.setLayoutManager(new LinearLayoutManager(this));
        rvNotifs.setAdapter(adapter);

        loadNotifs();
    }

    private void loadNotifs() {
        if (myUid.isEmpty()) return;
        notifListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeNotification> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeNotification n = ds.getValue(YouTubeNotification.class);
                    if (n != null) list.add(0, n);
                }
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.notificationsRef(myUid)
            .orderByChild("timestamp").limitToLast(50)
            .addValueEventListener(notifListener);
    }

    private void markRead(String notifId) {
        if (myUid.isEmpty() || notifId == null) return;
        YouTubeFirebaseUtils.notificationsRef(myUid).child(notifId).child("read").setValue(true);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (notifListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.notificationsRef(myUid).removeEventListener(notifListener);
    }
}
