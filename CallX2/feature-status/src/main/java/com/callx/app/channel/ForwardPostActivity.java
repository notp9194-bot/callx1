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
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ForwardPostActivity — forward a channel post to individual chats or groups.
 *
 * Full WhatsApp-level implementation:
 *   - Loads recent contacts + joined groups from Firebase
 *   - Tapping a target ACTUALLY sends the post content to the Firebase chat/group node
 *   - Shows checkmarks for multi-select (select multiple targets)
 *   - Shows "via [Channel]" attribution in the forwarded message
 *   - System share sheet fallback ("Share via other apps")
 *   - Records forward count on the source post
 */
public class ForwardPostActivity extends AppCompatActivity {

    public static final String EXTRA_POST_TEXT       = "postText";
    public static final String EXTRA_POST_MEDIA_URL  = "postMediaUrl";
    public static final String EXTRA_POST_TYPE       = "postType";
    public static final String EXTRA_CHANNEL_NAME    = "channelName";
    public static final String EXTRA_CHANNEL_ID      = "channelId";
    public static final String EXTRA_POST_ID         = "postId";

    private ChannelViewModel viewModel;
    private String postText, postMediaUrl, postType, channelName, channelId, postId;
    private ForwardAdapter adapter;
    private final List<ForwardTarget> allTargets      = new ArrayList<>();
    private final Set<String>         selectedIds     = new LinkedHashSet<>();
    private TextInputEditText         etSearch;
    private MaterialButton            btnForwardSelected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forward_post);

        postText     = getIntent().getStringExtra(EXTRA_POST_TEXT);
        postMediaUrl = getIntent().getStringExtra(EXTRA_POST_MEDIA_URL);
        postType     = getIntent().getStringExtra(EXTRA_POST_TYPE);
        channelName  = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        channelId    = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        postId       = getIntent().getStringExtra(EXTRA_POST_ID);

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_forward);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Forward to");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch           = findViewById(R.id.et_forward_search);
        btnForwardSelected = findViewById(R.id.btn_forward_selected);

        RecyclerView rv = findViewById(R.id.rv_forward_targets);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ForwardAdapter();
        rv.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    filterTargets(s.toString().trim().toLowerCase());
                }
            });
        }

        View btnShareSystem = findViewById(R.id.btn_share_via_other);
        if (btnShareSystem != null) btnShareSystem.setOnClickListener(v -> shareViaSystem());

        if (btnForwardSelected != null) {
            btnForwardSelected.setVisibility(View.GONE);
            btnForwardSelected.setOnClickListener(v -> forwardToSelected());
        }

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        loadTargets();
    }

    private void loadTargets() {
        String uid = FirebaseUtils.getCurrentUid();

        // Load contacts
        FirebaseUtils.getContactsRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {
                        String name    = ds.child("name").getValue(String.class);
                        String cUid    = ds.getKey();
                        String icon    = ds.child("photoUrl").getValue(String.class);
                        if (name != null && cUid != null)
                            allTargets.add(new ForwardTarget(cUid, name, "contact", icon != null ? icon : ""));
                    }
                    filterTargets("");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // Load groups
        FirebaseUtils.getUserGroupsRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {
                        String gId   = ds.getKey();
                        String gName = ds.child("name").getValue(String.class);
                        String icon  = ds.child("icon").getValue(String.class);
                        if (gId != null && gName != null)
                            allTargets.add(new ForwardTarget(gId, gName, "group", icon != null ? icon : ""));
                    }
                    filterTargets(etSearch != null && etSearch.getText() != null
                        ? etSearch.getText().toString().trim().toLowerCase() : "");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void filterTargets(String query) {
        List<ForwardTarget> filtered = new ArrayList<>();
        for (ForwardTarget t : allTargets) {
            if (query.isEmpty() || t.name.toLowerCase().contains(query)) filtered.add(t);
        }
        adapter.setTargets(filtered);
    }

    // ── Forward logic ─────────────────────────────────────────────────────

    /** Single-tap → forward immediately to one target. */
    private void forwardToTarget(ForwardTarget target) {
        sendToFirebase(target);
        if (channelId != null && postId != null) viewModel.recordForward(channelId, postId);
        Toast.makeText(this, "Forwarded to " + target.name, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Multi-select forward button. */
    private void forwardToSelected() {
        if (selectedIds.isEmpty()) return;
        int count = 0;
        for (ForwardTarget t : allTargets) {
            if (selectedIds.contains(t.id)) {
                sendToFirebase(t);
                count++;
            }
        }
        if (channelId != null && postId != null) viewModel.recordForward(channelId, postId);
        Toast.makeText(this, "Forwarded to " + count + " chat(s)", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void sendToFirebase(ForwardTarget target) {
        ChannelPost post = new ChannelPost();
        post.channelId  = channelId;
        post.id         = postId;
        post.text       = postText;
        post.mediaUrl   = postMediaUrl;
        post.type       = postType;
        viewModel.forwardPostToChat(target.id, "group".equals(target.type), post, channelName);
    }

    private void shareViaSystem() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        String content = postText != null && !postText.isEmpty() ? postText
            : (postMediaUrl != null ? postMediaUrl : "");
        if (channelName != null && !channelName.isEmpty())
            content = "[via " + channelName + " on CallX]\n" + content;
        share.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(share, "Forward post via"));
        if (channelId != null && postId != null) viewModel.recordForward(channelId, postId);
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class ForwardAdapter extends RecyclerView.Adapter<ForwardAdapter.VH> {
        private final List<ForwardTarget> targets = new ArrayList<>();

        void setTargets(List<ForwardTarget> list) {
            targets.clear(); targets.addAll(list); notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_forward_target, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ForwardTarget t = targets.get(pos);
            h.tvName.setText(t.name);
            h.tvType.setText("group".equals(t.type) ? "Group" : "");

            if (h.ivIcon != null && t.iconUrl != null && !t.iconUrl.isEmpty())
                Glide.with(ForwardPostActivity.this).load(t.iconUrl).circleCrop().into(h.ivIcon);
            else if (h.ivIcon != null)
                h.ivIcon.setImageResource(R.drawable.bg_channel_avatar_default);

            boolean selected = selectedIds.contains(t.id);
            if (h.ivCheck != null) h.ivCheck.setVisibility(selected ? View.VISIBLE : View.GONE);

            h.itemView.setOnClickListener(v -> {
                // Single-tap: if nothing selected → forward immediately
                if (selectedIds.isEmpty()) {
                    forwardToTarget(t);
                } else {
                    // Toggle selection
                    if (selectedIds.contains(t.id)) selectedIds.remove(t.id);
                    else selectedIds.add(t.id);
                    notifyItemChanged(pos);
                    updateForwardButton();
                }
            });

            h.itemView.setOnLongClickListener(v -> {
                // Long press → enter multi-select mode
                if (selectedIds.contains(t.id)) selectedIds.remove(t.id);
                else selectedIds.add(t.id);
                notifyItemChanged(pos);
                updateForwardButton();
                return true;
            });
        }

        @Override public int getItemCount() { return targets.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView   tvName, tvType;
            ImageView  ivCheck;
            VH(View v) {
                super(v);
                ivIcon  = v.findViewById(R.id.iv_forward_icon);
                tvName  = v.findViewById(R.id.tv_forward_name);
                tvType  = v.findViewById(R.id.tv_forward_type);
                ivCheck = v.findViewById(R.id.iv_forward_check);
            }
        }
    }

    private void updateForwardButton() {
        if (btnForwardSelected == null) return;
        if (selectedIds.isEmpty()) {
            btnForwardSelected.setVisibility(View.GONE);
        } else {
            btnForwardSelected.setVisibility(View.VISIBLE);
            btnForwardSelected.setText("Forward (" + selectedIds.size() + ")");
        }
    }

    // ── Data class ────────────────────────────────────────────────────────

    static class ForwardTarget {
        String id, name, type, iconUrl;
        ForwardTarget(String id, String name, String type, String iconUrl) {
            this.id = id; this.name = name; this.type = type; this.iconUrl = iconUrl;
        }
    }
}
