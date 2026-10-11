package com.callx.app.interactions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusRepliesBottomSheet v4 — PUBLIC comments list for a status (Instagram-style), perf pass.
 *
 * v511 behavior (unchanged): live list, PUBLIC "Reply" (@name comment, never a DM), owner-only tap →
 * private chat, owner/own-comment delete, long-press Copy/Delete, inline draft survives live updates.
 *
 * v512 perf pass:
 *  1) RecyclerView + ListAdapter/DiffUtil (was ScrollView + removeAllViews() on every live update):
 *     only the changed row rebinds, avatars are no longer re-requested for untouched rows.
 *  2) The inline-reply EditText + send button + TextWatcher are created LAZILY, on the first tap of
 *     "Reply" for that row holder (was: 3 extra views per comment, whether or not Reply was ever used).
 *  3) ChildEventListener on orderByChild("timestamp").limitToLast(PAGE_SIZE) (was: addValueEventListener
 *     on the whole node → every new comment re-downloaded + re-parsed ALL comments) + keyset
 *     pagination on scroll. Needs ".indexOn": ["timestamp"] on status/$uid/$statusId/replies (see rules).
 *  4) Avatars load through AvatarUrlBuilder#buildResponsive(TINY) + exact override() with the same
 *     options as ReelCommentAvatarBinder → same Glide cache key as reels comments (instant if warm).
 *  5) Story ring on every comment avatar (core StoryRingApplier state + drawable, painted on the row canvas).
 *  Also: theme colors / typeface resolved ONCE per sheet instead of per row (was 4x obtainStyledAttributes
 *  per row), live events are coalesced into one list submit per frame.
 */
public class StatusRepliesBottomSheet {
    private static final int MAX_COMMENT_LENGTH = 300; // same cap as the compose sheet / reels comments
    private static final int PAGE_SIZE = 30;
    /** Single background thread: Firebase snapshot parsing, text pre-layout and list sorting (v513). Ordered. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "status-replies-bg"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
    private static final AvatarSizeTier AVATAR_TIER = AvatarSizeTier.TINY; // 32dp — same tier as reels comments

    /** Immutable row model (DiffUtil compares by value). */
    static final class Row {
        final String key, uid, name, avatarUrl, text;
        final long ts;
        volatile StatusCommentCanvasView.Built built; // name/body StaticLayouts prebuilt on the bg thread (null → lazy)
        Row(String key, String uid, String name, String avatarUrl, String text, long ts) {
            this.key = key; this.uid = uid; this.name = name;
            this.avatarUrl = avatarUrl; this.text = text; this.ts = ts;
        }
        StatusItem.ReplyPreview toPreview() {
            return new StatusItem.ReplyPreview(uid, name, avatarUrl, text, ts);
        }
        static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
        boolean sameContent(Row o) {
            return ts == o.ts && eq(uid, o.uid) && eq(name, o.name)
                    && eq(avatarUrl, o.avatarUrl) && eq(text, o.text);
        }
    }

    /** Resolved ONCE per sheet (was per row). */
    static final class StyleHolder {
        int primary, secondary, divider;
        android.graphics.Typeface medium;
    }

    /** Per-sheet state: rows, paging, and the in-progress inline reply (survives live updates). */
    private static final class State {
        String openKey = null;
        String draft = "";
        final HashSet<String> sending = new HashSet<>();
        final HashMap<String, Row> rows = new HashMap<>();
        boolean initialLoaded, loadedAll, loading, flushQueued, dismissed, focusComposer;
        long oldestTs = 0;
    }

    private static final class Env {
        Context ctx; BottomSheetDialog sheet; String myUid, ownerUid; StatusItem item; boolean isOwner;
        StatusCommentCanvasView.Metrics metrics, bgMetrics; volatile int rowWidthPx; int gen;
        List<Row> lastSorted = new ArrayList<>(); RepliesCallbacks cbs;
        State state; StyleHolder style; int avatarPx; RequestOptions avatarOpts;
        RepliesAdapter adapter; RecyclerView recycler; View emptyState; TextView header;
        DatabaseReference ref; Handler main = new Handler(Looper.getMainLooper());
    }

    public static void show(Context ctx, String ownerUid, String myUid, StatusItem item, Runnable onDismiss) {
        final Env env = new Env();
        env.ctx = ctx; env.ownerUid = ownerUid; env.myUid = myUid; env.item = item;
        env.isOwner = myUid != null && myUid.equals(ownerUid);
        env.state = new State();
        env.style = resolveStyle(ctx);
        env.avatarPx = AvatarUrlBuilder.tierPx(ctx, AVATAR_TIER);
        env.metrics   = new StatusCommentCanvasView.Metrics(ctx, env.style);
        env.bgMetrics = new StatusCommentCanvasView.Metrics(ctx, env.style); // private paint copies for the bg thread
        // Row width before the first layout: sheet width (≤ 640dp) minus the 16dp side padding.
        env.rowWidthPx = Math.min(ctx.getResources().getDisplayMetrics().widthPixels, dp(ctx, 640)) - dp(ctx, 32);
        // Same options as ReelCommentAvatarBinder#bind → shared Glide memory/disk cache entry.
        env.avatarOpts = new RequestOptions()
                .override(env.avatarPx, env.avatarPx)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE);

        final BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        env.sheet = sheet;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));
        TextView header = new TextView(ctx);
        header.setTextSize(17);
        header.setTextColor(env.style.primary);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        root.addView(header);
        env.header = header;

        // Height-capped RecyclerView: wraps its content up to ~70% of the screen, and shrinks with the IME.
        final RecyclerView rv = new RecyclerView(ctx) {
            @Override protected void onMeasure(int w, int h) {
                int screen = getResources().getDisplayMetrics().heightPixels;
                int rootH = getRootView() != null ? getRootView().getHeight() : screen;
                if (rootH <= 0) rootH = screen;
                int max = Math.max(dp(ctx, 160), Math.min((int) (screen * 0.7f), rootH - dp(ctx, 140)));
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        rv.setItemAnimator(null); // live updates must not flicker
        rv.setHasFixedSize(false);
        rv.setItemViewCacheSize(6);
        env.cbs = new RepliesCallbacks(env);
        env.adapter = new RepliesAdapter(env);
        env.adapter.setHasStableIds(true);
        rv.setAdapter(env.adapter);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(ctx, 16));
        env.recycler = rv;
        rv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l; // real row width (RecyclerView has no horizontal padding); used for bg prebuild
            if (w > 0) env.rowWidthPx = w;
        });
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        env.emptyState = buildEmptyState(ctx, env);
        env.emptyState.setVisibility(View.GONE);
        root.addView(env.emptyState);
        sheet.setContentView(root);

        // 1) Seed instantly from what the viewer already has (its newest few), 2) then go live.
        if (item.replies != null) {
            for (Map.Entry<String, StatusItem.ReplyPreview> e : item.replies.entrySet()) {
                StatusItem.ReplyPreview p = e.getValue();
                if (p == null || e.getKey() == null) continue;
                env.state.rows.put(e.getKey(), new Row(e.getKey(), p.uid, p.name, p.avatarUrl, p.text,
                        p.timestamp != null ? p.timestamp : 0L));
            }
        }
        flush(env);

        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) r.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= env.adapter.getItemCount() - 5)
                    loadMore(env);
            }
        });

        env.ref = (item.id != null && !item.id.isEmpty() && ownerUid != null)
                ? FirebaseUtils.getStatusRepliesRef(ownerUid, item.id) : null;
        final Query liveQuery = env.ref == null ? null : liveQuery(env.ref);
        final ChildEventListener live = liveQuery == null ? null : new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String prev)   { upsert(env, s); }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String prev) { upsert(env, s); }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String prev)   {}
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                final String key = s.getKey();
                if (key == null || !env.state.rows.containsKey(key)) return;
                // A limitToLast window also fires "removed" when a NEW comment pushes the oldest one
                // OUT of the window — that row must stay (it's just older). Only drop real deletions.
                env.ref.child(key).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot d) {
                        if (!d.exists() && !env.state.dismissed) {
                            env.state.rows.remove(key);
                            scheduleFlush(env);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                android.util.Log.e("StatusReplies", "Live comments cancelled: " + e.getMessage());
            }
        };
        if (liveQuery != null) {
            liveQuery.addChildEventListener(live);
            // Fires once after the initial batch of onChildAdded → safe point for the empty state.
            liveQuery.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    env.state.initialLoaded = true;
                    if (s.getChildrenCount() < PAGE_SIZE) env.state.loadedAll = true;
                    scheduleFlush(env);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    env.state.initialLoaded = true; scheduleFlush(env);
                }
            });
        } else {
            env.state.initialLoaded = true; env.state.loadedAll = true; flush(env);
        }
        sheet.setOnDismissListener(d -> {
            env.state.dismissed = true;
            env.main.removeCallbacksAndMessages(null);
            if (liveQuery != null && live != null) liveQuery.removeEventListener(live);
            if (onDismiss != null) onDismiss.run();
        });
        // Keyboard: resize the sheet instead of letting the IME cover the inline reply box,
        // and open fully expanded (no half-collapsed state fighting the keyboard).
        if (sheet.getWindow() != null)
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.setOnShowListener(d -> {
            View parentView = (View) root.getParent();
            if (parentView != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> b =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(parentView);
                b.setSkipCollapsed(true);
                b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        sheet.show();
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    /** The exact query the sheet listens on. StatusViewerActivity keepSynced()s this same query while the
     *  status is on screen, so opening the sheet is served from the local cache (no network wait). */
    public static Query liveQuery(DatabaseReference repliesRef) {
        return repliesRef.orderByChild("timestamp").limitToLast(PAGE_SIZE);
    }

    /** Manual field reads — no reflection (getValue(Class)) per comment. */
    private static void upsert(Env env, DataSnapshot s) {
        // DataSnapshot is immutable → safe to read on the bg thread; result is applied on main, in order.
        BG.execute(() -> {
            final Row r = parse(env, s);
            if (r == null) return;
            env.main.post(() -> {
                if (env.state.dismissed) return;
                env.state.rows.put(r.key, r);
                scheduleFlush(env);
            });
        });
    }
    /** Background thread only. */
    private static Row parse(Env env, DataSnapshot s) {
        String key = s.getKey();
        if (key == null) return null;
        Object t = s.child("timestamp").getValue();
        long ts = t instanceof Number ? ((Number) t).longValue() : 0L;
        Row r = new Row(key, s.child("uid").getValue(String.class), s.child("name").getValue(String.class),
                s.child("avatarUrl").getValue(String.class), s.child("text").getValue(String.class), ts);
        final int w = env.rowWidthPx;
        if (w > 0) { // prebuild name/body StaticLayouts here → onMeasure on the main thread only reads heights
            try { r.built = StatusCommentCanvasView.build(env.bgMetrics, r, w); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Coalesces the burst of initial onChildAdded callbacks (and live bursts) into one submit. */
    private static void scheduleFlush(Env env) {
        if (env.state.flushQueued) return;
        env.state.flushQueued = true;
        env.main.post(() -> { env.state.flushQueued = false; if (!env.state.dismissed) flush(env); });
    }

    private static void flush(Env env) {
        final State st = env.state;
        final List<Row> snapshot = new ArrayList<>(st.rows.values()); // cheap copy on main
        final int gen = ++env.gen;
        BG.execute(() -> {
            Collections.sort(snapshot, (a, b) -> {
                int c = Long.compare(b.ts, a.ts); // newest first
                return c != 0 ? c : b.key.compareTo(a.key);
            });
            env.main.post(() -> { if (!st.dismissed && gen == env.gen) applyList(env, snapshot); });
        });
    }

    private static void applyList(Env env, List<Row> list) {
        final State st = env.state;
        long oldest = 0;
        for (Row r : list) if (r.ts > 0 && (oldest == 0 || r.ts < oldest)) oldest = r.ts;
        st.oldestTs = oldest;
        String count = list.size() + ((!st.loadedAll && list.size() >= PAGE_SIZE) ? "+" : "");
        env.header.setText("Comments (" + count + ")");
        boolean empty = list.isEmpty() && st.initialLoaded;
        env.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        env.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        env.lastSorted = list;
        List<Item> items = new ArrayList<>(list.size() + 1);
        for (Row r : list) {
            items.add(new Item(r, false));
            if (r.key.equals(st.openKey)) items.add(new Item(r, true)); // inline reply box right under its row
        }
        env.adapter.submitList(items);
    }

    /** Keyset pagination: next page of older comments, ordered by the indexed timestamp. */
    private static void loadMore(Env env) {
        final State st = env.state;
        if (env.ref == null || st.loading || st.loadedAll || !st.initialLoaded || st.oldestTs <= 0) return;
        st.loading = true;
        // endAt(oldestTs) is inclusive → PAGE_SIZE+1 so a full page still brings PAGE_SIZE new rows;
        // rows already held are deduped by key.
        env.ref.orderByChild("timestamp").endAt((double) st.oldestTs).limitToLast(PAGE_SIZE + 1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    st.loading = false;
                    if (st.dismissed) return;
                    BG.execute(() -> {
                        final List<Row> page = new ArrayList<>();
                        for (DataSnapshot c : s.getChildren()) {
                            Row r = parse(env, c);
                            if (r != null) page.add(r);
                        }
                        final boolean last = s.getChildrenCount() < PAGE_SIZE + 1;
                        env.main.post(() -> {
                            if (st.dismissed) return;
                            for (Row r : page) if (!st.rows.containsKey(r.key)) st.rows.put(r.key, r);
                            if (last) st.loadedAll = true;
                            flush(env);
                        });
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { st.loading = false; }
            });
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    /** List item: a comment row, or (at most one) the inline reply composer shown right under its row. */
    static final class Item {
        final Row row; final boolean composer;
        Item(Row row, boolean composer) { this.row = row; this.composer = composer; }
        String id() { return (composer ? "c:" : "r:") + row.key; }
    }

    private static final DiffUtil.ItemCallback<Item> DIFF = new DiffUtil.ItemCallback<Item>() {
        @Override public boolean areItemsTheSame(@NonNull Item a, @NonNull Item b) { return a.id().equals(b.id()); }
        @Override public boolean areContentsTheSame(@NonNull Item a, @NonNull Item b) {
            return a.composer ? Row.eq(a.row.name, b.row.name) : a.row.sameContent(b.row);
        }
    };

    /** Click/long-press/reply/delete dispatch for the canvas rows (one instance per sheet). */
    private static final class RepliesCallbacks implements StatusCommentCanvasView.Callbacks {
        final Env env;
        RepliesCallbacks(Env env) { this.env = env; }
        @Override public void onProfile(Row r) { if (openProfile(env.ctx, r)) env.sheet.dismiss(); }
        @Override public void onStory(Row r) {
            com.callx.app.utils.StoryRingApplier.openStatusViewer(env.ctx, r.uid, r.name);
        }
        @Override public void onReply(Row r) {
            final State st = env.state;
            if (r.key.equals(st.openKey)) { st.openKey = null; st.draft = ""; }
            else { st.openKey = r.key; st.draft = ""; st.focusComposer = true; }
            applyList(env, env.lastSorted); // no bg round-trip: the composer appears this frame
        }
        @Override public void onDelete(Row r) { showDeleteConfirm(env, r); }
        @Override public void onRowClick(Row r) { openChatWith(env.ctx, r); env.sheet.dismiss(); }
        @Override public void onLongPress(Row r) {
            final boolean mine = env.myUid != null && env.myUid.equals(r.uid);
            final boolean canDelete = env.isOwner || mine;
            final String[] labels = canDelete ? new String[]{"Copy", "Delete"} : new String[]{"Copy"};
            new android.app.AlertDialog.Builder(env.ctx).setItems(labels, (dlg, which) -> {
                if (which == 0) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            env.ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "comment", r.text != null ? r.text : ""));
                        Toast.makeText(env.ctx, "Copied", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    showDeleteConfirm(env, r);
                }
            }).show();
        }
    }

    private static final int TYPE_ROW = 0, TYPE_COMPOSER = 1;

    private static final class RepliesAdapter extends ListAdapter<Item, RecyclerView.ViewHolder> {
        private final Env env;
        RepliesAdapter(Env env) { super(DIFF); this.env = env; }
        @Override public long getItemId(int position) { return getItem(position).id().hashCode(); }
        @Override public int getItemViewType(int position) { return getItem(position).composer ? TYPE_COMPOSER : TYPE_ROW; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_COMPOSER) return new ComposerVH(env);
            return new RowVH(new StatusCommentCanvasView(env.ctx, env.metrics, env.cbs, env.isOwner));
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Item it = getItem(position);
            if (h instanceof ComposerVH) { ((ComposerVH) h).bind(it.row); return; }
            final Row r = it.row;
            final boolean mine = env.myUid != null && r.uid != null && env.myUid.equals(r.uid);
            ((RowVH) h).view.bind(r, env.myUid != null && r.uid != null && !mine, env.isOwner || mine);
        }
        @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder h) {
            if (h instanceof RowVH) ((RowVH) h).view.release();
            else if (h instanceof ComposerVH) ((ComposerVH) h).recycle();
        }
    }

    /** A comment = one canvas view (see StatusCommentCanvasView). */
    private static final class RowVH extends RecyclerView.ViewHolder {
        final StatusCommentCanvasView view;
        RowVH(StatusCommentCanvasView v) { super(v); view = v; }
    }

    /** Inline "Reply publicly to <name>" box — real EditText, one list item, only exists while a reply is open. */
    private static final class ComposerVH extends RecyclerView.ViewHolder {
        final Env env;
        final EditText et;
        final ImageButton sendBtn;
        Row row;

        ComposerVH(Env env) {
            super(new LinearLayout(env.ctx));
            this.env = env;
            final Context ctx = env.ctx;
            LinearLayout box = (LinearLayout) itemView;
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setPadding(env.metrics.colX, 0, 0, dp(ctx, 8)); // aligned with the comment text column
            et = new EditText(ctx);
            et.setTextSize(13);
            et.setTextColor(env.style.primary);
            et.setHintTextColor(env.style.secondary);
            et.setMaxLines(3);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(et);
            sendBtn = new ImageButton(ctx);
            sendBtn.setImageResource(android.R.drawable.ic_menu_send);
            int btnSz = dp(ctx, 36);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSz, btnSz);
            btnLp.setMarginStart(dp(ctx, 6));
            sendBtn.setLayoutParams(btnLp);
            sendBtn.setBackground(null);
            sendBtn.setContentDescription("Send reply");
            box.addView(sendBtn);
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (row != null && row.key.equals(env.state.openKey))
                        env.state.draft = e != null ? e.toString() : "";
                }
            });
            sendBtn.setOnClickListener(v -> sendReply());
        }

        void bind(Row r) {
            row = r;
            final String nm = (r.name != null && !r.name.isEmpty()) ? r.name : "someone";
            final String prefix = "@" + nm + " ";
            et.setHint("Reply publicly to " + nm + "…");
            // The "@name " prefix is added on send, so leave room for it inside the 300-char cap.
            et.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(Math.max(1, MAX_COMMENT_LENGTH - prefix.length()))});
            final boolean busy = env.state.sending.contains(r.key);
            sendBtn.setEnabled(!busy); sendBtn.setAlpha(busy ? 0.4f : 1f); et.setEnabled(!busy);
            if (!env.state.draft.contentEquals(et.getText())) {
                et.setText(env.state.draft);
                et.setSelection(et.getText().length());
            }
            if (env.state.focusComposer) {
                env.state.focusComposer = false;
                et.post(() -> {
                    et.requestFocus();
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                            env.ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                });
            }
            scrollInlineIntoView(itemView);
        }

        void recycle() {
            row = null;
            if (et.hasFocus()) et.clearFocus();
            if (et.length() > 0) et.setText("");
        }

        private void sendReply() {
            final Row r = row;
            if (r == null) return;
            String msg = et.getText() != null ? et.getText().toString().trim() : "";
            if (msg.isEmpty()) return;
            final String nm = (r.name != null && !r.name.isEmpty()) ? r.name : "someone";
            final String prefix = "@" + nm + " ";
            final String key = r.key;
            final Context ctx = env.ctx;
            final State st = env.state;
            st.sending.add(key);
            sendBtn.setEnabled(false); sendBtn.setAlpha(0.4f); et.setEnabled(false);
            // PUBLIC reply: a new comment "@name ..." — no private DM is created.
            StatusReplyBottomSheet.postPublicReply(env.myUid, env.ownerUid, env.item, prefix + msg, r.uid, res -> {
                st.sending.remove(key);
                final boolean stillMine = row != null && key.equals(row.key); // holder may have been recycled
                if (stillMine) { sendBtn.setEnabled(true); sendBtn.setAlpha(1f); et.setEnabled(true); }
                if (res == StatusReplyBottomSheet.RESULT_FAILED) {
                    Toast.makeText(ctx, "Couldn't post reply. Tap send to retry", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(ctx, res == StatusReplyBottomSheet.RESULT_POSTED
                        ? "Reply posted ✓" : "No connection — will post when you're back online",
                        Toast.LENGTH_SHORT).show();
                if (key.equals(st.openKey)) {
                    st.openKey = null; st.draft = "";
                    if (!st.dismissed) applyList(env, env.lastSorted); // composer item disappears
                }
            });
        }
    }

    // ── Static helpers ──────────────────────────────────────────────────────────

    private static StyleHolder resolveStyle(Context ctx) {
        StyleHolder s = new StyleHolder();
        android.content.res.TypedArray a = ctx.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        try {
            s.primary   = a.getColor(0, Color.BLACK);
            s.secondary = a.getColor(1, Color.GRAY);
        } finally { a.recycle(); }
        s.divider = (s.secondary & 0x00FFFFFF) | 0x22000000;
        s.medium  = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
        return s;
    }

    /** Empty state shown in the sheet itself (e.g. owner deleted the last comment). */
    private static View buildEmptyState(Context ctx, Env env) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(ctx, 32), 0, dp(ctx, 32));
        TextView icon = new TextView(ctx);
        icon.setText("💬"); // 💬
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);
        TextView title = new TextView(ctx);
        title.setText("No comments yet");
        title.setTextSize(15);
        title.setTypeface(env.style.medium);
        title.setTextColor(env.style.primary);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(ctx, 8);
        box.addView(title, tLp);
        TextView sub = new TextView(ctx);
        sub.setText(env.isOwner ? "Comments on your status will show up here" : "Be the first to comment");
        sub.setTextSize(13);
        sub.setTextColor(env.style.secondary);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(ctx, 2);
        box.addView(sub, sLp);
        return box;
    }

    /** After the IME resizes the sheet, bring the inline reply box fully on screen. */
    private static void scrollInlineIntoView(View inlineRow) {
        inlineRow.postDelayed(() -> {
            if (!inlineRow.isShown()) return;
            android.graphics.Rect r = new android.graphics.Rect(0, 0, inlineRow.getWidth(), inlineRow.getHeight());
            inlineRow.requestRectangleOnScreen(r, false);
        }, 250); // wait for the keyboard animation / resize
    }

    /** Confirms, then removes this one comment from the public
     *  status/{ownerUid}/{statusId}/replies/{replyKey} node. The live listener refreshes the list. */
    private static void showDeleteConfirm(Env env, Row r) {
        final Context ctx = env.ctx;
        final String statusId = env.item.id;
        final boolean isMine = env.myUid != null && env.myUid.equals(r.uid);
        if (r.key == null || r.key.isEmpty()) return;
        new android.app.AlertDialog.Builder(ctx)
            .setTitle(isMine ? "Delete your comment?" : "Delete comment?")
            .setMessage(isMine
                    ? "This can't be undone."
                    : "Remove " + (r.name != null ? r.name : "this") + "'s comment from your story. This can't be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                if (statusId == null || statusId.isEmpty()) return;
                FirebaseUtils.getStatusRepliesRef(env.ownerUid, statusId).child(r.key)
                    .removeValue()
                    .addOnSuccessListener(u ->
                        Toast.makeText(ctx, "Comment deleted", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                        Toast.makeText(ctx, "Couldn't delete comment", Toast.LENGTH_SHORT).show());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    /** Opens the commenter's profile. feature-status has no compile-time dependency on the app module,
     *  so it goes by class name (same pattern as reels MentionSpanUtils.navigateToProfile). */
    private static boolean openProfile(Context ctx, Row r) {
        if (r.uid == null || r.uid.isEmpty()) return false;
        try {
            Intent i = new Intent();
            i.setClassName(ctx, "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid",   r.uid);
            i.putExtra("name",  r.name != null ? r.name : "");
            i.putExtra("photo", r.avatarUrl != null ? r.avatarUrl : "");
            if (!(ctx instanceof android.app.Activity))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    /** Jumps straight into the DM thread with this commenter — the same chat the
     *  reply was already delivered into as a quoted status-reply bubble. */
    private static void openChatWith(Context ctx, Row r) {
        if (r.uid == null) return;
        Intent i = new Intent(ctx, com.callx.app.conversation.ChatActivity.class);
        i.putExtra("partnerUid",   r.uid);
        i.putExtra("partnerName",  r.name != null ? r.name : "");
        i.putExtra("partnerPhoto", r.avatarUrl != null ? r.avatarUrl : "");
        i.putExtra("partnerThumb", r.avatarUrl != null ? r.avatarUrl : "");
        if (!(ctx instanceof android.app.Activity))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
