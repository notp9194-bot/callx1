package com.callx.app.followers;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.cache.MutualFollowersCache;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.VerifiedBadgeUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;

/**
 * DiscoverPeopleActivity — Instagram-style "Discover people" full list,
 * opened from the Home feed's "Suggested popular accounts" card block via
 * its "See all" link (see HomeFragment's SuggestedAccountsRowHolder).
 *
 * Unlike {@link SuggestedListActivity} (which is scoped to one target
 * user's followers/following/suggested, with tabs), this screen is
 * self-centric and flat, matching the reference screenshot: a
 * "Connect contacts" row up top, then a single scrolling list of popular
 * accounts to follow — avatar, name (+ verified badge), mutual-followers
 * line (or "Popular" when there are none), a Follow button, and a ✕ to
 * dismiss.
 *
 * Deliberately reuses rather than re-implements:
 *  - item_suggested_user.xml (already has avatar/name/subtitle/Follow/✕;
 *    only gained an optional verified badge + mutual mini-avatar this
 *    change, both hidden by default).
 *  - MutualFollowersCache (core) for the "N mutuals" line — the same cache
 *    the reel bio row and the Home feed's suggestion rows use.
 *  - VerifiedBadgeUtils (core) for the badge.
 *  - The same "users ordered by reelCount" candidate query HomeFragment's
 *    loadSuggestedCreators()/insertInlineSuggestedCreatorsRow() already use
 *    for "popular accounts" elsewhere in the app.
 */
public class DiscoverPeopleActivity extends AppCompatActivity {

    private static final int REQ_READ_CONTACTS = 4821;

    private ImageButton btnBack;
    private LinearLayout rowConnectContacts;
    private Button       btnConnect;
    private RecyclerView rvUsers;
    private ProgressBar  progressBar;
    private LinearLayout layoutEmpty;

    private final List<PersonItem> items = new ArrayList<>();
    private final Set<String> myFollowing = new HashSet<>();
    private PeopleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discover_people);

        btnBack            = findViewById(R.id.btn_back);
        rowConnectContacts = findViewById(R.id.row_connect_contacts);
        btnConnect         = findViewById(R.id.btn_connect);
        rvUsers            = findViewById(R.id.rv_users);
        progressBar        = findViewById(R.id.progress_bar);
        layoutEmpty        = findViewById(R.id.layout_empty);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        View.OnClickListener connectClick = v -> requestContactsPermission();
        if (btnConnect != null) btnConnect.setOnClickListener(connectClick);
        if (rowConnectContacts != null) rowConnectContacts.setOnClickListener(connectClick);

        adapter = new PeopleAdapter();
        if (rvUsers != null) {
            rvUsers.setLayoutManager(new LinearLayoutManager(this));
            rvUsers.setAdapter(adapter);

            // FIX (velocity-based prefetch): same wiring as
            // FollowConnectionsActivity's buildPage() — fast fling past this
            // list skips prefetch entirely, slow/deliberate scroll warms
            // several rows ahead via DiskCacheStrategy.DATA (bytes only,
            // decode deferred to a real bind once a row is actually visible).
            rvUsers.addOnScrollListener(new RecyclerView.OnScrollListener() {
                private long lastTimeMs = 0L;

                @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm == null) return;
                    int lastVisible = lm.findLastVisibleItemPosition();
                    if (lastVisible < 0) return;

                    long now = android.os.SystemClock.elapsedRealtime();
                    long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                    float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                    lastTimeMs = now;

                    FollowAvatarBinder.prefetch(DiscoverPeopleActivity.this, peopleAvatarSource(), lastVisible + 1, velocity);
                }
            });
        }

        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        loadMyFollowingThenCandidates();
    }

    private void requestContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Contacts synced", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.READ_CONTACTS}, REQ_READ_CONTACTS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_CONTACTS && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Contacts synced", Toast.LENGTH_SHORT).show();
        }
    }

    private String myUid() {
        try {
            com.google.firebase.auth.FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getUid() : null;
        } catch (Exception e) { return null; }
    }

    private void loadMyFollowingThenCandidates() {
        String myUid = myUid();
        if (myUid == null) { finishLoad(); return; }
        FirebaseUtils.getReelFollowsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        if (s.getKey() != null) myFollowing.add(s.getKey());
                    }
                    loadCandidates(myUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadCandidates(myUid); }
            });
    }

    /** Same "popular accounts" source (users ordered by reelCount) as
     *  HomeFragment's suggestedCreatorPool — kept as its own query here
     *  since this is a standalone entry point that may be opened without
     *  Home's feed session ever having loaded that pool yet. */
    private void loadCandidates(String myUid) {
        FirebaseUtils.db().getReference("users")
            .orderByChild("reelCount")
            .limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<DataSnapshot> ordered = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) ordered.add(s);
                    Collections.reverse(ordered); // most popular first

                    for (DataSnapshot s : ordered) {
                        String uid = s.getKey();
                        if (uid == null || uid.equals(myUid) || myFollowing.contains(uid)) continue;
                        String name  = s.child("name").getValue(String.class);
                        if (name == null) continue;
                        String photo = s.child("photoUrl").getValue(String.class);
                        String thumb = s.child("thumbUrl").getValue(String.class);
                        String finalPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        // FIX (deep avatar pipeline): denormalized for FollowAvatarBinder.url()'s
                        // responsive/version-tagged URL — same field FollowConnectionsActivity's
                        // parseUser() already reads for every other avatar-bearing screen.
                        Long avatarVer = s.child("avatarVersion").getValue(Long.class);
                        items.add(new PersonItem(uid, name, finalPhoto != null ? finalPhoto : "",
                                avatarVer != null ? avatarVer : 0L));
                    }
                    finishLoad();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(); }
            });
    }

    private void finishLoad() {
        if (isFinishing() || isDestroyed()) return;
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        if (layoutEmpty != null) layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Data class ─────────────────────────────────────────────────────
    static class PersonItem {
        final String uid, name, photo;
        final long avatarVersion; // denormalized for FollowAvatarBinder.url()'s responsive/version-tagged URL
        boolean followed = false;
        PersonItem(String uid, String name, String photo, long avatarVersion) {
            this.uid = uid; this.name = name; this.photo = photo;
            this.avatarVersion = avatarVersion;
        }
    }

    /** AvatarSource view over the currently-loaded list, for
     *  FollowAvatarBinder.prefetch() — mirrors FollowConnectionsActivity's
     *  followAvatarSource(). */
    private FollowAvatarBinder.AvatarSource peopleAvatarSource() {
        return new FollowAvatarBinder.AvatarSource() {
            @Override public String photo(int index) { return items.get(index).photo; }
            @Override public long avatarVersion(int index) { return items.get(index).avatarVersion; }
            @Override public int size() { return items.size(); }
        };
    }

    // ── Adapter ────────────────────────────────────────────────────────
    //
    // ── ULTRA-ADVANCED PERF PASS (mirrors HomeFragment's
    //    SuggestedAccountsTileAdapter — see its class doc for the full
    //    rationale) ──
    // 1) Zero per-bind listener allocations: btnFollow / btnDismiss /
    //    itemView click listeners used to be fresh lambdas built in EVERY
    //    onBindViewHolder call (capturing that bind's PersonItem + position).
    //    Scrolling this full "Discover people" list of up to ~30 rows used
    //    to allocate 3 new listener objects per row on every single scroll-
    //    back-into-view. They're now allocated exactly ONCE per ViewHolder
    //    in onCreateViewHolder and read the holder's own mutable `person`
    //    field at click time.
    // 2) Settle-delay bind: a fast fling down this list can bind/recycle a
    //    row in single-digit milliseconds. Name text + follow-button state
    //    are free (no I/O) and stay immediate; the Glide avatar decode,
    //    verified-badge lookup, and mutual-followers cache call are
    //    deferred and only fire if the row is still bound to the same
    //    person BIND_SETTLE_DELAY_MS later — a fling that blows straight
    //    past a row no longer pays for any of that work at all.
    private class PeopleAdapter extends RecyclerView.Adapter<PeopleAdapter.VH> {
        private static final long BIND_SETTLE_DELAY_MS = 120L;

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_suggested_user, parent, false);
            VH h = new VH(v);

            // ★ Allocated exactly once — reads h.person at click time
            // instead of a per-bind captured PersonItem/position (point 1).
            h.btnFollow.setOnClickListener(v2 -> {
                int p = h.getAdapterPosition();
                if (p == RecyclerView.NO_POSITION || p >= items.size()) return;
                toggleFollow(items.get(p), h);
            });
            h.btnDismiss.setOnClickListener(v2 -> {
                int p = h.getAdapterPosition();
                if (p < 0 || p >= items.size()) return;
                items.remove(p);
                notifyItemRemoved(p);
                if (layoutEmpty != null) layoutEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
            h.itemView.setOnClickListener(v2 -> {
                PersonItem u = h.person;
                if (u == null) return;
                Intent i = new Intent(DiscoverPeopleActivity.this, UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,  u.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME, u.name);
                if (u.photo != null && !u.photo.isEmpty())
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                startActivity(i);
            });

            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            PersonItem u = items.get(pos);
            h.person = u;

            // Cheap, instant part: name text + follow-button state (no I/O).
            h.tvName.setText(u.name);
            updateFollowBtn(h, u);

            // Deferred part: Glide avatar decode + verified badge + mutual-
            // followers lookup (see class doc, point 2). Cancel any stale
            // pending bind from this holder's previous person first (row
            // recycled/rebound before the delay fired).
            if (h.bindPending) {
                h.itemView.removeCallbacks(h.bindRunnable);
            }
            h.bindToken++; // invalidates any in-flight mutual-followers lookup for the old person
            h.bindPending = true;
            h.itemView.postDelayed(h.bindRunnable, BIND_SETTLE_DELAY_MS);
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            if (h.bindPending) {
                h.itemView.removeCallbacks(h.bindRunnable);
                h.bindPending = false;
            }
            if (!isFinishing() && !isDestroyed()) {
                // FIX (lifecycle-aware cancel): stops an in-flight avatar
                // request for a row that just scrolled off screen instead of
                // letting it keep competing for bandwidth/decode time against
                // whatever's now actually visible — same as
                // FollowConnectionsActivity's onViewRecycled().
                FollowAvatarBinder.cancel(DiscoverPeopleActivity.this, h.ivAvatar);
                Glide.with(DiscoverPeopleActivity.this).clear(h.ivMutualAvatar);
            }
            h.bindToken++; // invalidate any in-flight lookup for this holder
            h.person = null;
        }

        private void updateFollowBtn(VH h, PersonItem u) {
            h.btnFollow.setText(u.followed ? "Following" : "Follow");
            h.btnFollow.setSelected(u.followed);
        }

        private void toggleFollow(PersonItem u, VH h) {
            String myUid = myUid();
            if (myUid == null) return;
            u.followed = !u.followed;
            if (u.followed) {
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).setValue(true);
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).setValue(true);
                myFollowing.add(u.uid);
            } else {
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).removeValue();
                myFollowing.remove(u.uid);
            }
            updateFollowBtn(h, u);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar, ivMutualAvatar;
            ImageView       ivVerified;
            TextView        tvName, tvSubtitle;
            Button          btnFollow;
            ImageButton     btnDismiss;

            /** Current person this holder is showing — read by the
             *  once-allocated listeners/Runnable below instead of a fresh
             *  per-bind captured local (see class doc, point 1). */
            PersonItem person;
            /** Guards the async mutual-followers callback below against a
             *  recycled row landing a stale result — same rule as
             *  HomeFragment's SuggestedAccountsTileAdapter.CardHolder. */
            int bindToken = 0;
            boolean bindPending;

            /** Single Runnable for this holder's whole lifetime — reads
             *  this.person / this.bindToken at RUN time, so no lambda is
             *  allocated on every bind (see class doc, point 2). */
            final Runnable bindRunnable = () -> {
                bindPending = false;
                PersonItem u = person;
                if (u == null || isFinishing() || isDestroyed()) return;
                final int token = bindToken;

                // FIX (deep avatar pipeline): flat Glide.load()+circleCrop()
                // replaced with FollowAvatarBinder.bind() — the SAME shared
                // pipeline FollowConnectionsActivity's Followers/Following/
                // Mutual/Suggested rows use: density-aware SMALL tier sizing,
                // L2 memory + L3 disk bitmap reuse (survives
                // TRIM_MEMORY_MODERATE, instant on re-scroll/warm restart),
                // dedupe-by-URL-tag so a follow-state-only rebind doesn't
                // reissue an identical request, and analytics wiring. No
                // circleCrop() needed — ivAvatar is already a CircleImageView
                // that clips to a circle at draw time (see FollowAvatarBinder
                // .bind()'s own doc for why stacking circleCrop() on top would
                // just be a second, redundant bitmap on every decode).
                FollowAvatarBinder.bind(DiscoverPeopleActivity.this, ivAvatar, u.photo, u.avatarVersion, R.drawable.ic_person);

                VerifiedBadgeUtils.bindForUid(ivVerified, u.uid);

                // Mutual followers line — "Popular" (matching the reference
                // screenshot's fallback label) until/unless a real mutual
                // count resolves via the shared cache.
                tvSubtitle.setText("Popular");
                ivMutualAvatar.setVisibility(View.GONE);
                String myUid = myUid();
                if (myUid != null && !myUid.equals(u.uid)) {
                    MutualFollowersCache.getInstance().getMutualFollowers(myUid, u.uid, (uids, names, photos) -> {
                        if (token != bindToken || isFinishing() || isDestroyed()) return; // stale — recycled
                        int count = uids.size();
                        if (count <= 0) return;
                        tvSubtitle.setText(count == 1 ? "1 mutual" : count + " mutuals");
                        if (!photos.isEmpty() && !photos.get(0).isEmpty()) {
                            Glide.with(DiscoverPeopleActivity.this).load(photos.get(0))
                                .placeholder(R.drawable.ic_person).circleCrop()
                                .into(ivMutualAvatar);
                        } else {
                            ivMutualAvatar.setImageResource(R.drawable.ic_person);
                        }
                        ivMutualAvatar.setVisibility(View.VISIBLE);
                    });
                }
            };

            VH(View v) {
                super(v);
                ivAvatar      = v.findViewById(R.id.iv_avatar);
                ivVerified    = v.findViewById(R.id.iv_verified);
                ivMutualAvatar = v.findViewById(R.id.iv_mutual_avatar);
                tvName        = v.findViewById(R.id.tv_name);
                tvSubtitle    = v.findViewById(R.id.tv_subtitle);
                btnFollow     = v.findViewById(R.id.btn_follow);
                btnDismiss    = v.findViewById(R.id.btn_dismiss);
            }
        }
    }
}
