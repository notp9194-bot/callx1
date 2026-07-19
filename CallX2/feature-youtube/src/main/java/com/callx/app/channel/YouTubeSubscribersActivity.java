package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * YouTubeSubscribersActivity — Full subscriber list for a channel.
 * Loads subscribers from Firebase, shows avatar + name + subscribe-back button.
 */
public class YouTubeSubscribersActivity extends AppCompatActivity {

    private RecyclerView rvSubscribers;
    private SubscriberAdapter adapter;
    private TextView tvEmpty, tvTitle;
    private String channelUid, myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_subscribers);

        channelUid = getIntent().getStringExtra("uid");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (channelUid == null || channelUid.isEmpty()) channelUid = myUid;

        View btnBack = findViewById(R.id.btn_yt_subs_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvTitle = findViewById(R.id.tv_yt_subs_title);
        tvEmpty = findViewById(R.id.tv_yt_subs_empty);

        rvSubscribers = findViewById(R.id.rv_yt_subscribers);
        adapter = new SubscriberAdapter(myUid);
        rvSubscribers.setLayoutManager(new LinearLayoutManager(this));
        rvSubscribers.setAdapter(adapter);

        loadSubscriberCount();
        loadSubscribers();
    }

    private void loadSubscriberCount() {
        YouTubeFirebaseUtils.channelRef(channelUid).child("subscriberCount")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long count = snap.getValue(Long.class);
                    if (tvTitle != null)
                        tvTitle.setText("Subscribers" + (count != null && count > 0 ? " (" + formatCount(count) + ")" : ""));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadSubscribers() {
        YouTubeFirebaseUtils.subscribersRef(channelUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) uids.add(ds.getKey());

                    if (uids.isEmpty()) {
                        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
                    fetchUserDetails(uids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                }
            });
    }

    private void fetchUserDetails(List<String> uids) {
        List<SubscriberItem> items = new ArrayList<>();
        AtomicInteger done = new AtomicInteger(0);
        int total = uids.size();

        for (String uid : uids) {
            YouTubeFirebaseUtils.channelRef(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String name = snap.child("channelName").getValue(String.class);
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String handle = snap.child("handle").getValue(String.class);
                        Long subCount = snap.child("subscriberCount").getValue(Long.class);
                        if (name == null || name.isEmpty()) name = "Unknown Channel";
                        SubscriberItem item = new SubscriberItem(uid, name, photo,
                            handle, subCount != null ? subCount : 0L);
                        synchronized (items) { items.add(item); }

                        if (done.incrementAndGet() == total) {
                            items.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                            runOnUiThread(() -> adapter.setData(new ArrayList<>(items)));
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (done.incrementAndGet() == total) {
                            runOnUiThread(() -> adapter.setData(new ArrayList<>(items)));
                        }
                    }
                });
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── Model ─────────────────────────────────────────────────────────────────
    static class SubscriberItem {
        String uid, name, photoUrl, handle;
        long   subscriberCount;
        boolean isSubscribedBack;

        SubscriberItem(String uid, String name, String photoUrl, String handle, long subscriberCount) {
            this.uid = uid; this.name = name; this.photoUrl = photoUrl;
            this.handle = handle; this.subscriberCount = subscriberCount;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    class SubscriberAdapter extends RecyclerView.Adapter<SubscriberAdapter.VH> {
        private final String myUid;
        private List<SubscriberItem> data = new ArrayList<>();

        SubscriberAdapter(String myUid) { this.myUid = myUid; }

        void setData(List<SubscriberItem> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_yt_subscriber, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            SubscriberItem item = data.get(pos);
            h.tvName.setText(item.name);

            String meta = "";
            if (item.handle != null && !item.handle.isEmpty()) meta = item.handle;
            if (item.subscriberCount > 0) {
                String subs = formatCount(item.subscriberCount) + " subscribers";
                meta = meta.isEmpty() ? subs : meta + "  •  " + subs;
            }
            h.tvMeta.setText(meta);
            h.tvMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

            Glide.with(h.ivAvatar.getContext()).load(item.photoUrl).circleCrop()
                .placeholder(R.drawable.ic_person).override(96, 96).into(h.ivAvatar);

            // Check subscription status
            if (!myUid.isEmpty()) {
                YouTubeFirebaseUtils.subscriptionsRef(myUid).child(item.uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            boolean isSub = snap.exists();
                            item.isSubscribedBack = isSub;
                            h.btnSubscribeBack.setText(isSub ? "Subscribed" : "Subscribe");
                            h.btnSubscribeBack.setAlpha(isSub ? 0.6f : 1f);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            } else {
                h.btnSubscribeBack.setVisibility(View.GONE);
            }

            // Hide subscribe button if this is the current user or the channel itself
            if (item.uid.equals(myUid) || item.uid.equals(channelUid)) {
                h.btnSubscribeBack.setVisibility(View.GONE);
            } else {
                h.btnSubscribeBack.setVisibility(View.VISIBLE);
            }

            h.btnSubscribeBack.setOnClickListener(v -> {
                if (myUid.isEmpty()) {
                    Toast.makeText(v.getContext(), "Login karo pehle", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (item.isSubscribedBack) {
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).child(item.uid).removeValue();
                    YouTubeFirebaseUtils.subscribersRef(item.uid).child(myUid).removeValue();
                    YouTubeFirebaseUtils.channelRef(item.uid).child("subscriberCount")
                        .setValue(ServerValue.increment(-1));
                    item.isSubscribedBack = false;
                    h.btnSubscribeBack.setText("Subscribe");
                    h.btnSubscribeBack.setAlpha(1f);
                } else {
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).child(item.uid).setValue(true);
                    YouTubeFirebaseUtils.subscribersRef(item.uid).child(myUid).setValue(true);
                    YouTubeFirebaseUtils.channelRef(item.uid).child("subscriberCount")
                        .setValue(ServerValue.increment(1));
                    item.isSubscribedBack = true;
                    h.btnSubscribeBack.setText("Subscribed");
                    h.btnSubscribeBack.setAlpha(0.6f);
                }
            });

            h.itemView.setOnClickListener(v ->
                startActivity(new Intent(YouTubeSubscribersActivity.this, YouTubeChannelActivity.class)
                    .putExtra("uid", item.uid)));
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvMeta;
            Button btnSubscribeBack;
            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_yt_sub_avatar);
                tvName   = v.findViewById(R.id.tv_yt_sub_name);
                tvMeta   = v.findViewById(R.id.tv_yt_sub_meta);
                btnSubscribeBack = v.findViewById(R.id.btn_yt_sub_subscribe_back);
            }
        }
    }
}
