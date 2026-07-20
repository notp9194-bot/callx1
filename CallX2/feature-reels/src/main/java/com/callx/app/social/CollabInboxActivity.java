package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * CollabInboxActivity — Joint Collab Post invite inbox.
 *
 * Two tabs:
 *   RECEIVED — incoming invites where current user is the collaborator
 *   SENT     — outgoing invites where current user is the initiator
 */
public class CollabInboxActivity extends AppCompatActivity {

    private TextView    tabReceived, tabSent;
    private View        indicatorReceived, indicatorSent;
    private RecyclerView rv;
    private TextView    tvEmpty;
    private ProgressBar progressBar;

    private String myUid;
    private boolean showingReceived = true;

    private final List<CollabInviteItem> items = new ArrayList<>();
    private InviteAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collab_inbox);

        myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) { finish(); return; }

        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tabReceived        = findViewById(R.id.tab_received);
        tabSent            = findViewById(R.id.tab_sent);
        indicatorReceived  = findViewById(R.id.indicator_received);
        indicatorSent      = findViewById(R.id.indicator_sent);
        rv                 = findViewById(R.id.rv_collab_inbox);
        tvEmpty            = findViewById(R.id.tv_collab_inbox_empty);
        progressBar        = findViewById(R.id.progress_collab_inbox);

        adapter = new InviteAdapter(items, this::onInviteAction);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        tabReceived.setOnClickListener(v -> switchTab(true));
        tabSent.setOnClickListener(v -> switchTab(false));

        loadReceived();
    }

    private void switchTab(boolean received) {
        showingReceived = received;
        tabReceived.setAlpha(received ? 1f : 0.5f);
        tabSent.setAlpha(received ? 0.5f : 1f);
        if (indicatorReceived != null) indicatorReceived.setVisibility(received ? View.VISIBLE : View.INVISIBLE);
        if (indicatorSent != null)     indicatorSent.setVisibility(received ? View.INVISIBLE : View.VISIBLE);
        items.clear();
        adapter.notifyDataSetChanged();
        if (received) loadReceived(); else loadSent();
    }

    private void loadReceived() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("collabPostInvites").child(myUid)
            .orderByChild("createdAt")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    items.clear();
                    for (DataSnapshot s : snapshot.getChildren()) {
                        CollabInviteItem item = parseItem(s, false);
                        if (item != null) items.add(0, item); // newest first
                    }
                    progressBar.setVisibility(View.GONE);
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressBar.setVisibility(View.GONE);
                }
            });
    }

    private void loadSent() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("collabPostInvitesSent").child(myUid)
            .orderByChild("createdAt")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    items.clear();
                    for (DataSnapshot s : snapshot.getChildren()) {
                        CollabInviteItem item = parseItem(s, true);
                        if (item != null) items.add(0, item);
                    }
                    progressBar.setVisibility(View.GONE);
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressBar.setVisibility(View.GONE);
                }
            });
    }

    private CollabInviteItem parseItem(DataSnapshot s, boolean isSent) {
        if (!s.exists()) return null;
        CollabInviteItem item = new CollabInviteItem();
        item.inviteId     = s.child("inviteId").getValue() != null ? s.child("inviteId").getValue().toString() : s.getKey();
        item.reelId       = getString(s, "reelId");
        item.thumbUrl     = getString(s, "thumbUrl");
        item.status       = getString(s, "status");
        item.initiatorUid = getString(s, "initiatorUid");
        item.initiatorName= getString(s, "initiatorName");
        item.initiatorPhoto=getString(s, "initiatorPhoto");
        item.caption      = getString(s, "initiatorCaption");
        item.collaboratorUid = getString(s, "collaboratorUid");
        item.isSent       = isSent;
        if (item.reelId == null || item.reelId.isEmpty()) return null;
        return item;
    }

    private void onInviteAction(CollabInviteItem item, boolean accept) {
        if (!showingReceived) return; // can only act on received
        Intent i = new Intent(this, CollabPostAcceptActivity.class);
        i.putExtra(CollabPostAcceptActivity.EXTRA_INVITE_ID,        item.inviteId);
        i.putExtra(CollabPostAcceptActivity.EXTRA_REEL_ID,          item.reelId);
        i.putExtra(CollabPostAcceptActivity.EXTRA_INITIATOR_UID,    item.initiatorUid);
        i.putExtra(CollabPostAcceptActivity.EXTRA_INITIATOR_NAME,   item.initiatorName);
        i.putExtra(CollabPostAcceptActivity.EXTRA_INITIATOR_PHOTO,  item.initiatorPhoto);
        i.putExtra(CollabPostAcceptActivity.EXTRA_CAPTION,          item.caption);
        i.putExtra(CollabPostAcceptActivity.EXTRA_THUMB_URL,        item.thumbUrl);
        startActivity(i);
    }

    private static String getString(DataSnapshot s, String key) {
        Object v = s.child(key).getValue();
        return v != null ? v.toString() : "";
    }

    // ── Data model ─────────────────────────────────────────────────────────
    static class CollabInviteItem {
        String inviteId, reelId, thumbUrl, status;
        String initiatorUid, initiatorName, initiatorPhoto, caption;
        String collaboratorUid;
        boolean isSent;
    }

    // ── Adapter ────────────────────────────────────────────────────────────
    private static class InviteAdapter extends RecyclerView.Adapter<InviteAdapter.VH> {
        interface ActionListener { void onAction(CollabInviteItem item, boolean accept); }
        private final List<CollabInviteItem> items;
        private final ActionListener listener;
        InviteAdapter(List<CollabInviteItem> items, ActionListener l) {
            this.items = items; this.listener = l;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collab_request, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            CollabInviteItem item = items.get(pos);
            h.tvName.setText("@" + (item.initiatorName != null ? item.initiatorName : ""));
            h.tvCaption.setText(item.caption != null ? item.caption : "");
            if (item.thumbUrl != null && !item.thumbUrl.isEmpty())
                Glide.with(h.itemView.getContext()).load(item.thumbUrl).centerCrop().into(h.ivThumb);
            if (item.initiatorPhoto != null && !item.initiatorPhoto.isEmpty())
                Glide.with(h.itemView.getContext()).load(item.initiatorPhoto).circleCrop().into(h.ivAvatar);

            String status = item.status != null ? item.status : "pending";
            h.tvStatus.setText(status.substring(0, 1).toUpperCase() + status.substring(1));
            int statusColor = status.equals("accepted") ? 0xFF4CAF50 :
                              status.equals("declined") ? 0xFFF44336 : 0xFFFF9800;
            h.tvStatus.setTextColor(statusColor);

            if (!item.isSent && "pending".equals(status)) {
                h.btnAccept.setVisibility(View.VISIBLE);
                h.btnDecline.setVisibility(View.VISIBLE);
                h.btnAccept.setOnClickListener(v -> listener.onAction(item, true));
                h.btnDecline.setOnClickListener(v -> listener.onAction(item, false));
            } else {
                h.btnAccept.setVisibility(View.GONE);
                h.btnDecline.setVisibility(View.GONE);
            }
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            ImageView       ivThumb;
            CircleImageView ivAvatar;
            TextView        tvName, tvCaption, tvStatus;
            Button          btnAccept, btnDecline;
            VH(View v) {
                super(v);
                ivThumb    = v.findViewById(R.id.iv_collab_req_thumb);
                ivAvatar   = v.findViewById(R.id.iv_collab_req_avatar);
                tvName     = v.findViewById(R.id.tv_collab_req_name);
                tvCaption  = v.findViewById(R.id.tv_collab_req_caption);
                tvStatus   = v.findViewById(R.id.tv_collab_req_status);
                btnAccept  = v.findViewById(R.id.btn_collab_req_accept);
                btnDecline = v.findViewById(R.id.btn_collab_req_decline);
            }
        }
    }
}
