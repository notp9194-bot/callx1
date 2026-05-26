package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeNotificationAdapter;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeNotificationsActivity extends AppCompatActivity {

    private RecyclerView               rvNotifs;
    private YouTubeNotificationAdapter adapter;
    private Button                     btnMarkAll;
    private TextView                   tvEmpty;
    private String                     myUid;
    private ValueEventListener         notifListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_notifications);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_notif_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        btnMarkAll = findViewById(R.id.btn_yt_mark_all_read);
        tvEmpty    = findViewById(R.id.tv_yt_notif_empty);

        if (btnMarkAll != null)
            btnMarkAll.setOnClickListener(v -> markAllRead());

        adapter = new YouTubeNotificationAdapter(this, new ArrayList<>(), notif -> {
            markRead(notif.notifId);
            if (notif.videoId != null && !notif.videoId.isEmpty())
                startActivity(new Intent(this, YouTubePlayerActivity.class)
                    .putExtra("video_id", notif.videoId));
            else if ("subscribe".equals(notif.type) && notif.fromUid != null)
                startActivity(new Intent(this, YouTubeChannelActivity.class)
                    .putExtra("uid", notif.fromUid));
        });

        rvNotifs = findViewById(R.id.rv_yt_notifications);
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
                if (tvEmpty != null)
                    tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.notificationsRef(myUid)
            .orderByChild("timestamp").limitToLast(80)
            .addValueEventListener(notifListener);
    }

    private void markRead(String notifId) {
        if (myUid.isEmpty() || notifId == null) return;
        YouTubeFirebaseUtils.notificationsRef(myUid).child(notifId).child("read").setValue(true);
    }

    private void markAllRead() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.notificationsRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren())
                        ds.getRef().child("read").setValue(true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (notifListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.notificationsRef(myUid).removeEventListener(notifListener);
    }
}
