package com.callx.app.upload;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReelCaptionMentionController
 *
 * Instagram-style @mention for the caption field in ReelUploadActivity.
 *
 * Uses ListPopupWindow — Android's built-in anchored dropdown — instead of
 * an embedded RecyclerView, so no layout changes are required and the popup
 * reliably appears above or below the EditText regardless of keyboard state.
 *
 * Usage:
 *   controller = new ReelCaptionMentionController(etCaption, myUid);
 *   controller.attach();
 *   ...
 *   ArrayList<String> uids = controller.getMentionedUids(captionText);
 *   ...
 *   controller.onDestroy();
 */
public class ReelCaptionMentionController {

    /** Blue mention colour — same as Twitter/Instagram. */
    public static final int MENTION_COLOR = 0xFF1DA1F2;

    // ── Data model ────────────────────────────────────────────────────────

    public static class MentionUser {
        public final String uid, name, photoUrl;
        MentionUser(String uid, String name, String photoUrl) {
            this.uid      = uid      != null ? uid      : "";
            this.name     = name     != null ? name     : "";
            this.photoUrl = photoUrl != null ? photoUrl : "";
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final TextInputEditText  etCaption;
    private final String             myUid;
    private final Context            ctx;

    private ListPopupWindow          popup;
    private MentionDropdownAdapter   adapter;
    private TextWatcher              watcher;

    private boolean  attached        = false;
    private boolean  followersLoaded = false;
    private boolean  loading         = false;

    private final List<MentionUser>       allFollowers = new ArrayList<>();
    private final List<MentionUser>       filtered     = new ArrayList<>();
    /** token (no spaces) → uid, built as mentions are inserted */
    private final Map<String, String>     nameToUid    = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────

    public ReelCaptionMentionController(@NonNull TextInputEditText etCaption,
                                        @NonNull String myUid) {
        this.etCaption = etCaption;
        this.myUid     = myUid;
        this.ctx       = etCaption.getContext();
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Call from Activity.onCreate() after views are bound. */
    public void attach() {
        if (attached) return;
        attached = true;
        setupPopup();
        watcher = buildWatcher();
        etCaption.addTextChangedListener(watcher);
    }

    /** Dismiss popup manually (e.g. before navigating away). */
    public void dismiss() {
        if (popup != null) popup.dismiss();
    }

    /**
     * Scan caption for @Token and resolve to UIDs.
     * Call just before upload.
     */
    @NonNull
    public ArrayList<String> getMentionedUids(@NonNull String caption) {
        ArrayList<String> uids = new ArrayList<>();
        if (caption.isEmpty() || nameToUid.isEmpty()) return uids;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("@([\\w.]+)").matcher(caption);
        while (m.find()) {
            String tok = m.group(1);
            if (tok == null) continue;
            String uid = nameToUid.get(tok);
            if (uid == null) {
                for (Map.Entry<String, String> e : nameToUid.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(tok)) { uid = e.getValue(); break; }
                }
            }
            if (uid != null && !uid.equals(myUid) && !uids.contains(uid)) uids.add(uid);
        }
        return uids;
    }

    /** Call from Activity.onDestroy(). */
    public void onDestroy() {
        if (watcher != null) etCaption.removeTextChangedListener(watcher);
        if (popup   != null) popup.dismiss();
        attached = false;
    }

    // ── Popup setup ───────────────────────────────────────────────────────

    private void setupPopup() {
        adapter = new MentionDropdownAdapter();

        popup = new ListPopupWindow(ctx);
        popup.setAdapter(adapter);
        popup.setAnchorView(etCaption);
        popup.setModal(false);
        popup.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setBackgroundDrawable(new ColorDrawable(0xFF1E1E1E));
        // Show ABOVE the EditText so keyboard doesn't cover it
        // Use negative offset equal to popup height (3 rows ≈ 56dp each) + EditText height
        int rowH  = (int)(56 * ctx.getResources().getDisplayMetrics().density);
        int editH = etCaption.getHeight() > 0 ? etCaption.getHeight() : (int)(48 * ctx.getResources().getDisplayMetrics().density);
        popup.setVerticalOffset(-(rowH * 3 + editH));
        popup.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filtered.size()) {
                insertMention(filtered.get(position));
            }
        });
    }

    // ── TextWatcher ───────────────────────────────────────────────────────

    private TextWatcher buildWatcher() {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable ed) {
                String full   = ed.toString();
                int    cursor = etCaption.getSelectionStart();
                if (cursor < 0 || cursor > full.length()) { dismiss(); return; }

                // Walk backwards from cursor to find active "@"
                int atIdx = -1;
                for (int i = cursor - 1; i >= 0; i--) {
                    char ch = full.charAt(i);
                    if (ch == '@') { atIdx = i; break; }
                    if (Character.isWhitespace(ch)) break;
                }

                if (atIdx < 0) { dismiss(); return; }

                String query = full.substring(atIdx + 1, cursor);

                if (!followersLoaded && !loading) {
                    loading = true;
                    loadFollowers(() -> {
                        followersLoaded = true;
                        loading = false;
                        showFor(query);
                    });
                } else if (followersLoaded) {
                    showFor(query);
                }
            }
        };
    }

    // ── Firebase ──────────────────────────────────────────────────────────

    private void loadFollowers(@NonNull Runnable onDone) {
        // Correct path: reelFollowers/{myUid}/{followerUid} = true
        FirebaseUtils.db().getReference("reelFollowers").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allFollowers.clear();
                    nameToUid.clear();

                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        if (c.getKey() != null) uids.add(c.getKey());
                    }

                    if (uids.isEmpty()) {
                        // Fallback: try "following" path — people I follow
                        loadFollowing(onDone);
                        return;
                    }

                    int limit = Math.min(uids.size(), 80);
                    int[] remaining = {limit};

                    for (int i = 0; i < limit; i++) {
                        String uid = uids.get(i);
                        FirebaseUtils.db().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot s) {
                                    String name  = s.child("name").getValue(String.class);
                                    String photo = s.child("photoUrl").getValue(String.class);
                                    if (name != null && !name.isEmpty()) {
                                        allFollowers.add(new MentionUser(uid, name,
                                                photo != null ? photo : ""));
                                        nameToUid.put(name, uid);
                                        nameToUid.put(name.replace(" ", ""), uid);
                                    }
                                    if (--remaining[0] <= 0) onDone.run();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--remaining[0] <= 0) onDone.run();
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { onDone.run(); }
            });
    }

    /** Fallback: load people I follow (reelFollows/{myUid}/{uid}) for mention suggestions */
    private void loadFollowing(@NonNull Runnable onDone) {
        FirebaseUtils.db().getReference("reelFollows").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        if (c.getKey() != null) uids.add(c.getKey());
                    }
                    if (uids.isEmpty()) { onDone.run(); return; }

                    int limit = Math.min(uids.size(), 80);
                    int[] remaining = {limit};

                    for (int i = 0; i < limit; i++) {
                        String uid = uids.get(i);
                        FirebaseUtils.db().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot s) {
                                    String name  = s.child("name").getValue(String.class);
                                    String photo = s.child("photoUrl").getValue(String.class);
                                    if (name != null && !name.isEmpty()) {
                                        allFollowers.add(new MentionUser(uid, name,
                                                photo != null ? photo : ""));
                                        nameToUid.put(name, uid);
                                        nameToUid.put(name.replace(" ", ""), uid);
                                    }
                                    if (--remaining[0] <= 0) onDone.run();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--remaining[0] <= 0) onDone.run();
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { onDone.run(); }
            });
    }

    // ── Show / filter ─────────────────────────────────────────────────────

    private void showFor(String query) {
        filtered.clear();
        String lq = query.toLowerCase(Locale.getDefault());
        for (MentionUser u : allFollowers) {
            if (lq.isEmpty() || u.name.toLowerCase(Locale.getDefault()).contains(lq)) {
                filtered.add(u);
            }
        }
        if (filtered.isEmpty()) { dismiss(); return; }
        adapter.notifyDataSetChanged();
        if (!popup.isShowing()) popup.show();
    }

    // ── Insert mention ────────────────────────────────────────────────────

    private void insertMention(@NonNull MentionUser user) {
        dismiss();
        Editable ed = etCaption.getText();
        if (ed == null) return;

        int    cursor = etCaption.getSelectionStart();
        String full   = ed.toString();

        int atIdx = -1;
        for (int i = Math.min(cursor, full.length()) - 1; i >= 0; i--) {
            char ch = full.charAt(i);
            if (ch == '@') { atIdx = i; break; }
            if (Character.isWhitespace(ch)) break;
        }

        // Store both forms for later UID resolution
        String token    = user.name.replace(" ", "");
        String inserted = "@" + token + " ";
        nameToUid.put(token, user.uid);
        nameToUid.put(user.name, user.uid);

        int insertAt = atIdx >= 0 ? atIdx : ed.length();
        int replaceEnd = atIdx >= 0 ? cursor : ed.length();

        ed.replace(insertAt, replaceEnd, inserted);
        ed.setSpan(
                new ForegroundColorSpan(MENTION_COLOR),
                insertAt, insertAt + inserted.length() - 1,   // exclude trailing space
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ── Dropdown Adapter ──────────────────────────────────────────────────

    private class MentionDropdownAdapter extends BaseAdapter {

        @Override public int getCount()              { return filtered.size(); }
        @Override public MentionUser getItem(int p)  { return filtered.get(p); }
        @Override public long getItemId(int p)       { return p; }

        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ViewHolder vh;
            if (convertView == null) {
                convertView = buildRow(parent.getContext());
                vh = new ViewHolder(convertView);
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }
            MentionUser u = filtered.get(position);
            vh.tvName.setText(u.name);
            vh.tvHandle.setText("@" + u.name.replace(" ", "").toLowerCase(Locale.getDefault()));
            if (!u.photoUrl.isEmpty()) {
                Glide.with(ctx)
                     .load(u.photoUrl)
                     .transform(new CircleCrop())
                     .placeholder(buildAvatarPlaceholder())
                     .override(480, 853)
                     .into(vh.ivAvatar);
            } else {
                vh.ivAvatar.setImageDrawable(buildAvatarPlaceholder());
            }
            return convertView;
        }

        /** Build a row programmatically — no extra layout file needed. */
        private View buildRow(Context c) {
            float dp = c.getResources().getDisplayMetrics().density;
            int   pad = (int)(12 * dp);
            int   gap = (int)(10 * dp);
            int   avatarSz = (int)(40 * dp);

            android.widget.LinearLayout row = new android.widget.LinearLayout(c);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, (int)(8*dp), pad, (int)(8*dp));
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            ImageView iv = new ImageView(c);
            iv.setTag("avatar");
            android.widget.LinearLayout.LayoutParams ivLp =
                    new android.widget.LinearLayout.LayoutParams(avatarSz, avatarSz);
            iv.setLayoutParams(ivLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(iv);

            android.widget.LinearLayout textCol = new android.widget.LinearLayout(c);
            textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams tcLp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tcLp.setMarginStart(gap);
            textCol.setLayoutParams(tcLp);

            TextView tvName = new TextView(c);
            tvName.setTag("name");
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setTextSize(14f);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvName.setSingleLine(true);
            textCol.addView(tvName);

            TextView tvHandle = new TextView(c);
            tvHandle.setTag("handle");
            tvHandle.setTextColor(0xFF9E9E9E);
            tvHandle.setTextSize(12f);
            tvHandle.setSingleLine(true);
            textCol.addView(tvHandle);

            row.addView(textCol);
            return row;
        }

        private android.graphics.drawable.ShapeDrawable buildAvatarPlaceholder() {
            android.graphics.drawable.ShapeDrawable d =
                    new android.graphics.drawable.ShapeDrawable(
                            new android.graphics.drawable.shapes.OvalShape());
            d.getPaint().setColor(0xFF444444);
            return d;
        }

        private static class ViewHolder {
            final ImageView ivAvatar;
            final TextView  tvName, tvHandle;
            ViewHolder(View v) {
                ivAvatar = v.findViewWithTag("avatar");
                tvName   = v.findViewWithTag("name");
                tvHandle = v.findViewWithTag("handle");
            }
        }
    }
}
