package com.callx.app.social;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v790 — Screen 3 "Select Post". Pick which public video to Remix / Collab with (the post the flow started from
 * is pre-selected). Tabs: For You (latest), Following (people I follow), Trending (most liked). Search filters
 * the current tab by creator name or caption. Posts whose creator turned Remix off, private / close-friends
 * posts, photo slideshows and my own posts are not offered.
 */
public class RemixSelectPostActivity extends AppCompatActivity {

    private static final int LOAD_LIMIT = 150;
    private static final int TAB_FOR_YOU = 0, TAB_FOLLOWING = 1, TAB_TRENDING = 2;

    private static final class Item {
        String reelId, uid, ownerName, ownerPhoto, videoUrl, thumbUrl, caption;
        long likes, ts;
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final Set<String> following = new HashSet<>();

    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvEmpty, tvSelTitle, tvSelOwner, tabForYou, tabFollowing, tabTrending;
    private ImageView ivSelected;
    private View btnNext;
    private PostAdapter adapter;

    private String mode = RemixCollabTarget.MODE_REMIX;
    private RemixCollabTarget entry;
    private Item selected;
    private int tab = TAB_FOR_YOU;
    private String query = "";
    private String myUid;
    private boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remix_select_post);
        myUid = FirebaseUtils.getCurrentUid();
        entry = RemixCollabTarget.from(getIntent());
        if (RemixCollabTarget.MODE_COLLAB.equals(getIntent().getStringExtra(RemixCollabTarget.X_MODE)))
            mode = RemixCollabTarget.MODE_COLLAB;

        rv           = findViewById(R.id.rv_posts);
        progress     = findViewById(R.id.progress);
        tvEmpty      = findViewById(R.id.tv_empty);
        tvSelTitle   = findViewById(R.id.tv_selected_title);
        tvSelOwner   = findViewById(R.id.tv_selected_owner);
        ivSelected   = findViewById(R.id.iv_selected);
        btnNext      = findViewById(R.id.btn_next);
        tabForYou    = findViewById(R.id.tab_for_you);
        tabFollowing = findViewById(R.id.tab_following);
        tabTrending  = findViewById(R.id.tab_trending);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        adapter = new PostAdapter();
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setAdapter(adapter);

        tabForYou.setOnClickListener(v -> setTab(TAB_FOR_YOU));
        tabFollowing.setOnClickListener(v -> setTab(TAB_FOLLOWING));
        tabTrending.setOnClickListener(v -> setTab(TAB_TRENDING));

        ((EditText) findViewById(R.id.et_search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
        });

        btnNext.setOnClickListener(v -> {
            if (selected == null) return;
            RemixCollabTarget t = new RemixCollabTarget(selected.reelId, selected.uid, selected.ownerName,
                    selected.ownerPhoto, selected.videoUrl, selected.thumbUrl);
            RemixCollabFlow.next(this, t, mode);
        });

        refreshSelectionBar();
        loadFollowing();
    }

    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }

    private void loadFollowing() {
        if (myUid == null) { loadPosts(); return; }
        FirebaseUtils.getReelFollowsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                for (DataSnapshot c : s.getChildren())
                    if (c.getKey() != null && Boolean.TRUE.equals(c.getValue(Boolean.class))) following.add(c.getKey());
                loadPosts();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { loadPosts(); }
        });
    }

    private void loadPosts() {
        FirebaseUtils.getReelsRef().orderByChild("timestamp").limitToLast(LOAD_LIMIT)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (destroyed || isFinishing()) return;
                        all.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            Item it = parse(c);
                            if (it != null) all.add(it);
                        }
                        Collections.sort(all, (a, b) -> Long.compare(b.ts, a.ts));
                        ensureEntryPost();
                        progress.setVisibility(View.GONE);
                        applyFilter();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (destroyed || isFinishing()) return;
                        progress.setVisibility(View.GONE);
                        ensureEntryPost();
                        applyFilter();
                    }
                });
    }

    @Nullable
    private Item parse(DataSnapshot c) {
        String uid = c.child("uid").getValue(String.class);
        String video = c.child("videoUrl").getValue(String.class);
        if (c.getKey() == null || uid == null || uid.isEmpty() || uid.equals(myUid)) return null;
        if (video == null || video.isEmpty()) return null;
        String mediaType = c.child("mediaType").getValue(String.class);
        if (mediaType != null && !mediaType.isEmpty() && !"video".equals(mediaType)) return null; // no photo slideshows
        String aud = c.child("audienceType").getValue(String.class);
        if (aud != null && !aud.isEmpty() && !"everyone".equals(aud)) return null;                  // private / close friends
        if ("off".equals(c.child("remix_settings").child("remix").getValue(String.class))) return null;
        Item it = new Item();
        it.reelId = c.getKey();
        it.uid = uid;
        it.ownerName = nz(c.child("ownerName").getValue(String.class));
        it.ownerPhoto = nz(c.child("ownerPhoto").getValue(String.class));
        it.videoUrl = video;
        String th = c.child("thumbUrl").getValue(String.class);
        if (th == null || th.isEmpty()) th = c.child("thumbnailUrl").getValue(String.class);
        it.thumbUrl = nz(th);
        it.caption = nz(c.child("caption").getValue(String.class));
        Long likes = c.child("likesCount").getValue(Long.class);
        it.likes = likes == null ? 0L : likes;
        Long ts = c.child("timestamp").getValue(Long.class);
        it.ts = ts == null ? 0L : ts;
        return it;
    }

    /** The post the flow started from is always selectable, even if it is older than the loaded window. */
    private void ensureEntryPost() {
        if (!entry.isValid() || entry.ownerUid.equals(myUid) && RemixCollabTarget.MODE_COLLAB.equals(mode)) return;
        Item found = null;
        for (Item it : all) if (it.reelId.equals(entry.reelId)) { found = it; break; }
        if (found == null) {
            found = new Item();
            found.reelId = entry.reelId; found.uid = entry.ownerUid; found.ownerName = entry.ownerName;
            found.ownerPhoto = entry.ownerPhoto; found.videoUrl = entry.videoUrl; found.thumbUrl = entry.thumbUrl;
            found.caption = ""; found.likes = 0; found.ts = Long.MAX_VALUE;
            all.add(0, found);
        }
        selected = found;
        refreshSelectionBar();
    }

    private void setTab(int t) {
        tab = t;
        style(tabForYou, t == TAB_FOR_YOU);
        style(tabFollowing, t == TAB_FOLLOWING);
        style(tabTrending, t == TAB_TRENDING);
        applyFilter();
    }

    private void style(TextView tv, boolean on) {
        tv.setBackgroundResource(on ? R.drawable.rc_bg_chip_on : R.drawable.rc_bg_chip_off);
        tv.setTextColor(on ? 0xFFFFFFFF : getResources().getColor(R.color.rc_text));
    }

    private void applyFilter() {
        shown.clear();
        for (Item it : all) {
            if (tab == TAB_FOLLOWING && !following.contains(it.uid)) continue;
            if (!query.isEmpty()
                    && !it.ownerName.toLowerCase(Locale.getDefault()).contains(query)
                    && !it.caption.toLowerCase(Locale.getDefault()).contains(query)) continue;
            shown.add(it);
        }
        if (tab == TAB_TRENDING) Collections.sort(shown, (a, b) -> Long.compare(b.likes, a.likes));
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(shown.isEmpty() && progress.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private void refreshSelectionBar() {
        boolean has = selected != null;
        btnNext.setEnabled(has);
        tvSelTitle.setText(has ? R.string.rc_selected_one : R.string.rc_selected_none);
        tvSelOwner.setText(has && !selected.ownerName.isEmpty() ? "@" + selected.ownerName : "");
        if (has && !selected.thumbUrl.isEmpty()) Glide.with(this).load(selected.thumbUrl).centerCrop().into(ivSelected);
        else ivSelected.setImageDrawable(null);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    private final class PostAdapter extends RecyclerView.Adapter<PostAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_remix_select_post, p, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            final Item it = shown.get(pos);
            boolean sel = selected != null && selected.reelId.equals(it.reelId);
            h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.ring.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.likes.setText(compact(it.likes));
            h.owner.setText(it.ownerName.isEmpty() ? "" : "@" + it.ownerName);
            if (!it.thumbUrl.isEmpty()) Glide.with(h.thumb).load(it.thumbUrl).centerCrop().into(h.thumb);
            else h.thumb.setImageDrawable(null);
            h.itemView.setOnClickListener(v -> {
                selected = it;
                refreshSelectionBar();
                notifyDataSetChanged();
            });
        }

        @Override public int getItemCount() { return shown.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView thumb, check;
            final View ring;
            final TextView likes, owner;
            VH(@NonNull View v) {
                super(v);
                thumb = v.findViewById(R.id.iv_thumb);
                check = v.findViewById(R.id.iv_check);
                ring  = v.findViewById(R.id.v_selected_ring);
                likes = v.findViewById(R.id.tv_likes);
                owner = v.findViewById(R.id.tv_owner);
            }
        }
    }
}
