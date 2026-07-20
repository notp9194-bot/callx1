package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.ChannelPost;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.textfield.TextInputEditText;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ForwardPostActivity — forward a channel post to a chat, group, or status (v5).
 *
 * v5 additions:
 *   ✓ NEW: "Share to my Status" — first row at top of list; shares post to user's story feed
 *     via ChannelViewModel.sharePostToStatus()
 *   ✓ NEW: Share externally via Android share sheet (link only)
 *   ✓ Forward to 1-on-1 chats (contacts tab)
 *   ✓ Forward to groups (groups tab)
 *   ✓ Optional forward note / caption
 *   ✓ Search contacts / groups by name
 *   ✓ Multi-select up to 5 recipients
 *   ✓ Send button shows recipient count
 *   ✓ Tapping "Share to Status" immediately shares and shows a success toast
 */
public class ForwardPostActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID    = "channelId";
    public static final String EXTRA_POST_ID       = "postId";
    public static final String EXTRA_POST_TYPE     = "postType";
    public static final String EXTRA_POST_TEXT     = "postText";
    public static final String EXTRA_POST_MEDIA_URL= "postMediaUrl";

    private ChannelViewModel viewModel;
    private ChannelPost      post;

    private ForwardAdapter   adapter;
    private final List<ForwardTarget>   allTargets      = new ArrayList<>();
    private final List<ForwardTarget>   filteredTargets = new ArrayList<>();
    private final Set<String>           selectedIds     = new LinkedHashSet<>();

    private TextInputEditText etSearch, etForwardNote;
    private com.google.android.material.button.MaterialButton btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forward_post);

        String channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        String postId      = getIntent().getStringExtra(EXTRA_POST_ID);
        String postType    = getIntent().getStringExtra(EXTRA_POST_TYPE);
        String postText    = getIntent().getStringExtra(EXTRA_POST_TEXT);
        String postMediaUrl= getIntent().getStringExtra(EXTRA_POST_MEDIA_URL);
        if (channelId == null || postId == null) { finish(); return; }

        post = new ChannelPost();
        post.channelId = channelId; post.id = postId;
        post.type = postType; post.text = postText; post.mediaUrl = postMediaUrl;

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_forward);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Forward to");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch      = findViewById(R.id.et_forward_search);
        etForwardNote = findViewById(R.id.et_forward_note);
        btnSend       = findViewById(R.id.btn_forward_send);

        RecyclerView rv = findViewById(R.id.rv_forward_targets);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ForwardAdapter();
        rv.setAdapter(adapter);

        // Search
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    filterTargets(s.toString().trim().toLowerCase());
                }
            });
        }

        // Send button
        if (btnSend != null) {
            btnSend.setEnabled(false);
            btnSend.setOnClickListener(v -> sendForward());
        }

        // Build target list
        buildTargets();

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    // ── Build target list ─────────────────────────────────────────────────

    private void buildTargets() {
        allTargets.clear();

        // ── NEW: "Share to my Status" special entry at top ─────────────
        ForwardTarget statusTarget = new ForwardTarget();
        statusTarget.id       = "__status__";
        statusTarget.name     = "My Status";
        statusTarget.subtitle = "Share as a 24h story";
        statusTarget.type     = "status";
        statusTarget.isSpecial= true;
        allTargets.add(statusTarget);

        // ── Share externally ───────────────────────────────────────────
        ForwardTarget shareTarget = new ForwardTarget();
        shareTarget.id       = "__share__";
        shareTarget.name     = "Share externally";
        shareTarget.subtitle = "Share link via any app";
        shareTarget.type     = "external";
        shareTarget.isSpecial= true;
        allTargets.add(shareTarget);

        // ── Load contacts from Firebase ────────────────────────────────
        String myUid = FirebaseUtils.getMyUid();
        if (myUid == null) { adapter.setData(allTargets); return; }

        FirebaseUtils.db().getReference("userContacts").child(myUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                    for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                        ForwardTarget t = new ForwardTarget();
                        t.id   = child.getKey();
                        Object n = child.child("name").getValue(); t.name = n != null ? n.toString() : t.id;
                        Object i = child.child("iconUrl").getValue(); t.iconUrl = i != null ? i.toString() : null;
                        t.type = "chat";
                        allTargets.add(t);
                    }
                    loadGroups();
                }
                @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                    loadGroups();
                }
            });
    }

    private void loadGroups() {
        String myUid = FirebaseUtils.getMyUid();
        if (myUid == null) { filteredTargets.addAll(allTargets); adapter.setData(filteredTargets); return; }

        FirebaseUtils.db().getReference("userGroups").child(myUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                    for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                        ForwardTarget t = new ForwardTarget();
                        t.id   = child.getKey();
                        Object n = child.child("name").getValue(); t.name = n != null ? n.toString() : "Group";
                        Object i = child.child("iconUrl").getValue(); t.iconUrl = i != null ? i.toString() : null;
                        t.type = "group";
                        allTargets.add(t);
                    }
                    filteredTargets.clear(); filteredTargets.addAll(allTargets);
                    adapter.setData(filteredTargets);
                }
                @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                    filteredTargets.clear(); filteredTargets.addAll(allTargets);
                    adapter.setData(filteredTargets);
                }
            });
    }

    // ── Filter ────────────────────────────────────────────────────────────

    private void filterTargets(String q) {
        filteredTargets.clear();
        for (ForwardTarget t : allTargets) {
            if (t.isSpecial || q.isEmpty() || (t.name != null && t.name.toLowerCase().contains(q)))
                filteredTargets.add(t);
        }
        adapter.setData(filteredTargets);
    }

    // ── Send / share ──────────────────────────────────────────────────────

    private void sendForward() {
        String note = etForwardNote != null && etForwardNote.getText() != null
            ? etForwardNote.getText().toString().trim() : "";

        for (String id : selectedIds) {
            if ("__status__".equals(id)) {
                // NEW: share to user's own status story
                viewModel.sharePostToStatus(post);
            } else if ("__share__".equals(id)) {
                ChannelShareHelper.shareViaAndroid(this, post, null);
            } else {
                // Find type
                String type = "chat";
                for (ForwardTarget t : allTargets) if (id.equals(t.id)) { type = t.type; break; }
                viewModel.forwardPostToChat(id, type, post, note);
            }
        }
        Toast.makeText(this, selectedIds.size() == 1 ? "Forwarded!" : "Forwarded to " + selectedIds.size() + " chats.", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    // ── ForwardAdapter ────────────────────────────────────────────────────

    class ForwardAdapter extends RecyclerView.Adapter<ForwardAdapter.VH> {
        private final List<ForwardTarget> data = new ArrayList<>();

        void setData(List<ForwardTarget> d) { data.clear(); data.addAll(d); notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == 1
                ? R.layout.item_forward_target_special
                : R.layout.item_forward_target;
            return new VH(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        }

        @Override public int getItemViewType(int pos) { return data.get(pos).isSpecial ? 1 : 0; }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ForwardTarget t = data.get(pos);
            if (h.tvName     != null) h.tvName.setText(t.name != null ? t.name : "");
            if (h.tvSubtitle != null) h.tvSubtitle.setText(t.subtitle != null ? t.subtitle : "");
            if (h.ivCheck    != null) h.ivCheck.setVisibility(selectedIds.contains(t.id) ? View.VISIBLE : View.GONE);
            if (h.ivIcon != null && t.iconUrl != null && !t.iconUrl.isEmpty())
                Glide.with(h.ivIcon.getContext()).load(t.iconUrl).circleCrop().into(h.ivIcon);
            else if (h.ivIcon != null && t.isSpecial) {
                if ("__status__".equals(t.id)) h.ivIcon.setImageResource(R.drawable.ic_status_story);
                else                            h.ivIcon.setImageResource(android.R.drawable.ic_menu_share);
            }
            if (h.tvType != null && !t.isSpecial) h.tvType.setText("group".equals(t.type) ? "Group" : "");

            h.itemView.setOnClickListener(v -> {
                if (t.isSpecial && "status".equals(t.type)) {
                    // Immediate share to status
                    viewModel.sharePostToStatus(post);
                    Toast.makeText(ForwardPostActivity.this, "Shared to your Status!", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (t.isSpecial && "external".equals(t.type)) {
                    ChannelShareHelper.shareViaAndroid(ForwardPostActivity.this, post, null);
                    return;
                }
                if (selectedIds.contains(t.id)) selectedIds.remove(t.id);
                else if (selectedIds.size() < 5) selectedIds.add(t.id);
                else Toast.makeText(ForwardPostActivity.this, "Max 5 recipients.", Toast.LENGTH_SHORT).show();
                notifyItemChanged(pos);
                if (btnSend != null) {
                    btnSend.setEnabled(!selectedIds.isEmpty());
                    btnSend.setText(selectedIds.isEmpty() ? "Send" : "Send (" + selectedIds.size() + ")");
                }
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            ImageView ivCheck;
            TextView tvName, tvSubtitle, tvType;
            VH(View v) {
                super(v);
                ivIcon     = v.findViewById(R.id.iv_forward_icon);
                ivCheck    = v.findViewById(R.id.iv_forward_check);
                tvName     = v.findViewById(R.id.tv_forward_name);
                tvSubtitle = v.findViewById(R.id.tv_forward_subtitle);
                tvType     = v.findViewById(R.id.tv_forward_type);
            }
        }
    }

    static class ForwardTarget {
        String id, name, subtitle, iconUrl, type;
        boolean isSpecial = false;
    }
}
