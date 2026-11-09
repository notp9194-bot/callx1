package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v790 — Remix &amp; Collab requests inbox.
 *  Received: pending requests on MY reels → Approve / Reject; approved ones → Revoke (takes the permission back).
 *  Sent: my own requests with their live status → tap opens the status screen.
 */
public class RemixRequestsInboxActivity extends AppCompatActivity {

    private final List<RemixRequestHelper.Request> rows = new ArrayList<>();
    private final Map<String, Boolean> approvalAlive = new HashMap<>();   // "reelId/requesterUid" → approval still exists
    private final Map<String, String> ownerNames = new HashMap<>();       // uid → name (Sent tab)

    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvEmpty, tabReceived, tabSent;
    private Adapter adapter;
    private boolean sentTab;
    private String me;
    private int loadToken;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remix_requests_inbox);
        me = FirebaseUtils.getCurrentUid();
        rv = findViewById(R.id.rv);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tv_empty);
        tabReceived = findViewById(R.id.tab_received);
        tabSent = findViewById(R.id.tab_sent);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        adapter = new Adapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        tabReceived.setOnClickListener(v -> setTab(false));
        tabSent.setOnClickListener(v -> setTab(true));
        if (getIntent().getBooleanExtra("open_sent", false)) setTab(true);   // opened from an approved / declined push
    }

    @Override protected void onResume() { super.onResume(); load(); }

    private void setTab(boolean sent) {
        sentTab = sent;
        tabReceived.setBackgroundResource(sent ? R.drawable.rc_bg_chip_off : R.drawable.rc_bg_chip_on);
        tabSent.setBackgroundResource(sent ? R.drawable.rc_bg_chip_on : R.drawable.rc_bg_chip_off);
        tabReceived.setTextColor(sent ? getResources().getColor(R.color.rc_text) : 0xFFFFFFFF);
        tabSent.setTextColor(sent ? 0xFFFFFFFF : getResources().getColor(R.color.rc_text));
        load();
    }

    private void load() {
        if (me == null) return;
        final int token = ++loadToken;
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        if (sentTab) loadSent(token); else loadReceived(token);
    }

    private boolean stale(int token) { return token != loadToken || isFinishing() || isDestroyed(); }

    private void loadReceived(final int token) {
        FirebaseUtils.db().getReference("remixRequests").child(me).orderByChild("ts").limitToLast(100)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (stale(token)) return;
                        List<RemixRequestHelper.Request> list = new ArrayList<>();
                        for (DataSnapshot c : s.getChildren()) {
                            RemixRequestHelper.Request r = RemixRequestHelper.Request.from(me, c);
                            if (r != null) list.add(r);
                        }
                        Collections.sort(list, (a, c) -> {
                            boolean ap = RemixRequestHelper.PENDING.equals(a.effectiveStatus());
                            boolean cp = RemixRequestHelper.PENDING.equals(c.effectiveStatus());
                            if (ap != cp) return ap ? -1 : 1;
                            return Long.compare(c.ts, a.ts);
                        });
                        checkApprovals(list, token);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(new ArrayList<>(), token); }
                });
    }

    /** For approved rows: is the approval still there (not revoked)? */
    private void checkApprovals(final List<RemixRequestHelper.Request> list, final int token) {
        final List<RemixRequestHelper.Request> approved = new ArrayList<>();
        for (RemixRequestHelper.Request r : list)
            if (RemixRequestHelper.APPROVED.equals(r.status)) approved.add(r);
        if (approved.isEmpty()) { finishLoad(list, token); return; }
        final int[] left = { approved.size() };
        for (final RemixRequestHelper.Request r : approved) {
            FirebaseUtils.db().getReference("remixApprovals").child(r.reelId).child(r.requesterUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot a) {
                            approvalAlive.put(r.reelId + "/" + r.requesterUid, a.exists());
                            if (--left[0] == 0) finishLoad(list, token);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            if (--left[0] == 0) finishLoad(list, token);
                        }
                    });
        }
    }

    private void loadSent(final int token) {
        FirebaseUtils.db().getReference("remixRequestsSent").child(me).orderByChild("ts").limitToLast(50)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (stale(token)) return;
                        final List<DataSnapshot> idx = new ArrayList<>();
                        for (DataSnapshot c : s.getChildren()) idx.add(c);
                        if (idx.isEmpty()) { finishLoad(new ArrayList<>(), token); return; }
                        final List<RemixRequestHelper.Request> list = new ArrayList<>();
                        final int[] left = { idx.size() };
                        for (DataSnapshot c : idx) {
                            final String id = c.getKey();
                            final String owner = c.child("ownerUid").getValue(String.class);
                            if (id == null || owner == null) { if (--left[0] == 0) finishSent(list, token); continue; }
                            FirebaseUtils.db().getReference("remixRequests").child(owner).child(id)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot rs) {
                                            RemixRequestHelper.Request r = RemixRequestHelper.Request.from(owner, rs);
                                            if (r != null) list.add(r);
                                            if (--left[0] == 0) finishSent(list, token);
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            if (--left[0] == 0) finishSent(list, token);
                                        }
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(new ArrayList<>(), token); }
                });
    }

    private void finishSent(List<RemixRequestHelper.Request> list, int token) {
        Collections.sort(list, (a, c) -> Long.compare(c.ts, a.ts));
        finishLoad(list, token);
    }

    private void finishLoad(List<RemixRequestHelper.Request> list, int token) {
        if (stale(token)) return;
        rows.clear();
        rows.addAll(list);
        progress.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        tvEmpty.setText(sentTab ? "You haven't sent any requests yet" : "No requests yet");
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadOwnerName(final String uid, final TextView into) {
        String cached = ownerNames.get(uid);
        if (cached != null) { into.setText(cached); return; }
        into.setText("Creator");
        FirebaseUtils.db().getReference("users").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String n = s.child("name").getValue(String.class);
                if (n == null || n.isEmpty()) n = s.child("username").getValue(String.class);
                if (n == null || n.isEmpty()) return;
                ownerNames.put(uid, n);
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_remix_request, p, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            final RemixRequestHelper.Request r = rows.get(pos);
            final boolean collab = "collab".equals(r.mode);
            final String st = r.effectiveStatus();
            h.mode.setText(collab ? "COLLAB" : "REMIX");
            h.message.setText(r.message == null ? "" : r.message);
            if (r.thumbUrl != null && !r.thumbUrl.isEmpty()) Glide.with(h.thumb).load(r.thumbUrl).centerCrop().into(h.thumb);
            else h.thumb.setImageDrawable(null);
            h.actions.setVisibility(View.GONE);
            h.primary.setEnabled(true);

            if (sentTab) {
                h.avatar.setImageResource(R.drawable.ic_person);
                loadOwnerName(r.ownerUid, h.name);
                h.status.setText(label(st));
                h.itemView.setOnClickListener(v -> {
                    Intent i = new Intent(RemixRequestsInboxActivity.this, RemixRequestStatusActivity.class);
                    new RemixCollabTarget(r.reelId, r.ownerUid, ownerNames.get(r.ownerUid), "", "", r.thumbUrl).put(i);
                    i.putExtra(RemixCollabTarget.X_MODE, collab ? RemixCollabTarget.MODE_COLLAB : RemixCollabTarget.MODE_REMIX);
                    i.putExtra(RemixCollabTarget.X_REQUEST_ID, r.id);
                    startActivity(i);
                });
                return;
            }

            h.itemView.setOnClickListener(null);
            h.name.setText(r.requesterName == null || r.requesterName.isEmpty() ? "Someone" : r.requesterName);
            if (r.requesterPhoto != null && !r.requesterPhoto.isEmpty())
                Glide.with(h.avatar).load(r.requesterPhoto).circleCrop().into(h.avatar);
            else h.avatar.setImageResource(R.drawable.ic_person);
            h.status.setText(label(st));

            if (RemixRequestHelper.PENDING.equals(st)) {
                h.actions.setVisibility(View.VISIBLE);
                h.secondary.setText(R.string.rc_reject);
                h.primary.setText(R.string.rc_approve);
                h.secondary.setOnClickListener(v -> decide(r, false, h));
                h.primary.setOnClickListener(v -> decide(r, true, h));
            } else if (RemixRequestHelper.APPROVED.equals(st)) {
                boolean alive = !Boolean.FALSE.equals(approvalAlive.get(r.reelId + "/" + r.requesterUid));
                if (alive) {
                    h.actions.setVisibility(View.VISIBLE);
                    h.secondary.setVisibility(View.GONE);
                    h.primary.setText(R.string.rc_revoke);
                    h.primary.setOnClickListener(v -> confirmRevoke(r));
                } else {
                    h.status.setText("Permission revoked");
                }
            }
            h.secondary.setVisibility(h.actions.getVisibility() == View.VISIBLE
                    && RemixRequestHelper.PENDING.equals(st) ? View.VISIBLE : View.GONE);
        }

        @Override public int getItemCount() { return rows.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView avatar, thumb;
            final TextView name, mode, message, status;
            final View actions;
            final Button primary, secondary;
            VH(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.iv_avatar);
                thumb = v.findViewById(R.id.iv_thumb);
                name = v.findViewById(R.id.tv_name);
                mode = v.findViewById(R.id.tv_mode);
                message = v.findViewById(R.id.tv_message);
                status = v.findViewById(R.id.tv_status);
                actions = v.findViewById(R.id.row_actions);
                primary = v.findViewById(R.id.btn_primary);
                secondary = v.findViewById(R.id.btn_secondary);
            }
        }
    }

    private String label(String st) {
        switch (st) {
            case RemixRequestHelper.APPROVED:  return "Approved";
            case RemixRequestHelper.REJECTED:  return "Declined";
            case RemixRequestHelper.EXPIRED:   return "Expired";
            case RemixRequestHelper.CANCELLED: return "Cancelled";
            default: return "Pending";
        }
    }

    private void decide(final RemixRequestHelper.Request r, final boolean approve, final Adapter.VH h) {
        h.primary.setEnabled(false);
        h.secondary.setEnabled(false);
        RemixRequestHelper.decide(r, approve, ok -> {
            if (isFinishing() || isDestroyed()) return;
            if (!ok) { toast("Couldn't update. Try again"); h.primary.setEnabled(true); h.secondary.setEnabled(true); return; }
            toast(approve ? "Approved" : "Declined");
            load();
        });
    }

    private void confirmRevoke(final RemixRequestHelper.Request r) {
        new AlertDialog.Builder(this)
                .setTitle("Revoke permission?")
                .setMessage("This person won't be able to publish a "
                        + ("collab".equals(r.mode) ? "collab" : "remix") + " of this post anymore. "
                        + "Posts they already published stay until you remove them from the post's remixes list.")
                .setPositiveButton("Revoke", (d, w) -> RemixRequestHelper.revoke(r.reelId, r.requesterUid, ok -> {
                    if (isFinishing() || isDestroyed()) return;
                    approvalAlive.put(r.reelId + "/" + r.requesterUid, !ok);
                    toast(ok ? "Permission revoked" : "Couldn't revoke. Try again");
                    adapter.notifyDataSetChanged();
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
