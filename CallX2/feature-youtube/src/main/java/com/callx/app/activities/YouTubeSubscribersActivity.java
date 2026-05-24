package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/** Full subscribers list with avatar, channel name and link to profile. */
public class YouTubeSubscribersActivity extends AppCompatActivity {

    private RecyclerView rvSubs;
    private SubsAdapter  adapter;
    private String       channelUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_subscribers);

        channelUid = getIntent().getStringExtra("uid");
        if (channelUid == null) {
            channelUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        }

        View btnBack = findViewById(R.id.btn_yt_subs_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        TextView tvTitle = findViewById(R.id.tv_yt_subs_title);
        if (tvTitle != null) tvTitle.setText("Subscribers");

        rvSubs  = findViewById(R.id.rv_yt_subscribers);
        adapter = new SubsAdapter();
        rvSubs.setLayoutManager(new LinearLayoutManager(this));
        rvSubs.setAdapter(adapter);

        loadSubscribers();
    }

    private void loadSubscribers() {
        YouTubeFirebaseUtils.subscribersRef(channelUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) uids.add(ds.getKey());
                    loadProfiles(uids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadProfiles(List<String> uids) {
        List<SubscriberItem> items = new ArrayList<>();
        if (uids.isEmpty()) { adapter.setData(items); return; }
        final int[] count = {0};
        for (String uid : uids) {
            YouTubeFirebaseUtils.channelRef(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String name  = snap.child("channelName").getValue(String.class);
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String handle= snap.child("handle").getValue(String.class);
                        Long subs    = snap.child("subscriberCount").getValue(Long.class);
                        if (name != null)
                            items.add(new SubscriberItem(uid, name, photo, handle, subs != null ? subs : 0));
                        count[0]++;
                        if (count[0] == uids.size()) {
                            items.sort((a, b) -> Long.compare(b.subscriberCount, a.subscriberCount));
                            adapter.setData(items);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        count[0]++;
                        if (count[0] == uids.size()) adapter.setData(items);
                    }
                });
        }
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    static class SubscriberItem {
        String uid, name, photoUrl, handle;
        long   subscriberCount;
        SubscriberItem(String uid, String name, String photoUrl,
                       String handle, long subscriberCount) {
            this.uid = uid; this.name = name; this.photoUrl = photoUrl;
            this.handle = handle; this.subscriberCount = subscriberCount;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class SubsAdapter extends RecyclerView.Adapter<SubsAdapter.VH> {
        private List<SubscriberItem> data = new ArrayList<>();
        void setData(List<SubscriberItem> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(YouTubeSubscribersActivity.this)
                .inflate(R.layout.item_yt_subscriber, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            SubscriberItem item = data.get(pos);
            h.tvName.setText(item.name);
            h.tvHandle.setText(item.handle != null ? "@" + item.handle : "");
            h.tvSubs.setText(formatCount(item.subscriberCount) + " subscribers");
            if (item.photoUrl != null)
                Glide.with(YouTubeSubscribersActivity.this)
                    .load(item.photoUrl).circleCrop().into(h.ivAvatar);
            h.itemView.setOnClickListener(v ->
                startActivity(new Intent(YouTubeSubscribersActivity.this,
                    YouTubeChannelActivity.class).putExtra("uid", item.uid)));
        }
        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvHandle, tvSubs;
            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_yt_sub_avatar);
                tvName   = v.findViewById(R.id.tv_yt_sub_name);
                tvHandle = v.findViewById(R.id.tv_yt_sub_handle);
                tvSubs   = v.findViewById(R.id.tv_yt_sub_subs);
            }
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
