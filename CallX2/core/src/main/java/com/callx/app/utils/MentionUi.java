package com.callx.app.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import de.hdodenhof.circleimageview.CircleImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @mention UI shared by the status mention sticker picker and the composer's text/caption autocomplete.
 *  - {@link #showPicker}: search dialog (contacts when empty, username-prefix search while typing).
 *  - {@link #attachAutocomplete}: "@na…" suggestions popup under any EditText.
 */
public final class MentionUi {
    private MentionUi() {}

    public interface OnPick { void onPick(@NonNull MentionUserSearch.Result user); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_MS = 250L;

    // ── Row ────────────────────────────────────────────────────────────────
    private static final class RowAdapter extends BaseAdapter {
        private final Context ctx; private final List<MentionUserSearch.Result> data = new ArrayList<>();
        RowAdapter(Context c) { ctx = c; }
        void set(List<MentionUserSearch.Result> r) { data.clear(); data.addAll(r); notifyDataSetChanged(); }
        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            final float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout row; CircleImageView av; TextView name, user;
            if (v == null) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
                av = new CircleImageView(ctx);
                av.setTag("av");
                row.addView(av, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));
                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.setMarginStart((int) (12 * d));
                name = new TextView(ctx); name.setTag("name"); name.setTextSize(15); name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
                user = new TextView(ctx); user.setTag("user"); user.setTextSize(13); user.setSingleLine(true);
                user.setTextColor(Color.GRAY);
                col.addView(name); col.addView(user);
                row.addView(col, clp);
                v = row;
            }
            av = v.findViewWithTag("av"); name = v.findViewWithTag("name"); user = v.findViewWithTag("user");
            MentionUserSearch.Result r = data.get(i);
            name.setText(r.name); user.setText("@" + r.username);
            if (r.photo != null && !r.photo.isEmpty()) {
                Glide.with(ctx).load(r.photo).override((int) (80 * d)).dontAnimate()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE).into(av);
            } else {
                Glide.with(ctx).clear(av);
                av.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            return v;
        }
    }

    // ── Picker dialog (mention sticker) ────────────────────────────────────
    public static void showPicker(@NonNull Context ctx, @NonNull OnPick onPick) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (4 * d));

        final EditText et = new EditText(ctx);
        et.setHint("Search username");
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(et);

        final TextView empty = new TextView(ctx);
        empty.setTextColor(Color.GRAY); empty.setTextSize(13); empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (24 * d), 0, (int) (24 * d));
        root.addView(empty);

        final ListView list = new ListView(ctx);
        list.setDividerHeight(0);
        final RowAdapter adapter = new RowAdapter(ctx);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (320 * d)));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setTitle("👤 Mention someone")
                .setView(root).setNegativeButton("Cancel", null).create();

        final int[] seq = {0};
        final Runnable[] pending = {null};
        final Runnable run = () -> {
            final int my = ++seq[0];
            final String q = et.getText().toString().trim().replaceFirst("^@", "");
            empty.setText("Searching…"); empty.setVisibility(View.VISIBLE);
            MentionUserSearch.search(q, results -> {
                if (my != seq[0]) return; // stale
                adapter.set(results);
                empty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
                empty.setText(q.isEmpty() ? "No contacts to suggest — type a username"
                        : "No user found for “@" + q + "”");
            });
        };
        et.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (pending[0] != null) MAIN.removeCallbacks(pending[0]);
                pending[0] = run; MAIN.postDelayed(run, DEBOUNCE_MS);
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            onPick.onPick((MentionUserSearch.Result) adapter.getItem(pos));
            dlg.dismiss();
        });
        dlg.setOnDismissListener(x -> { if (pending[0] != null) MAIN.removeCallbacks(pending[0]); seq[0]++; });
        dlg.show();
        run.run(); // contacts first
    }

    // ── Autocomplete under an EditText (status text / caption) ─────────────
    private static final Pattern TOKEN = Pattern.compile("(?:^|[\\s(])@([A-Za-z0-9_]{0,30})$");

    public static void attachAutocomplete(@NonNull final EditText et) {
        final Context ctx = et.getContext();
        final ListPopupWindow popup = new ListPopupWindow(ctx);
        final RowAdapter adapter = new RowAdapter(ctx);
        popup.setAdapter(adapter);
        popup.setAnchorView(et);
        popup.setModal(false); // keep typing in the EditText
        popup.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NEEDED);
        final int[] seq = {0};
        final int[] tokenStart = {-1};
        final Runnable[] pending = {null};

        popup.setOnItemClickListener((p, v, pos, id) -> {
            MentionUserSearch.Result r = (MentionUserSearch.Result) adapter.getItem(pos);
            Editable ed = et.getText();
            int cur = Math.min(et.getSelectionStart(), ed.length());
            if (tokenStart[0] >= 0 && tokenStart[0] <= cur) {
                ed.replace(tokenStart[0], cur, "@" + r.username + " ");
            }
            popup.dismiss();
        });

        et.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                int cur = et.getSelectionStart();
                if (cur < 0) { popup.dismiss(); return; }
                Matcher m = TOKEN.matcher(s.subSequence(0, cur));
                if (!m.find()) { seq[0]++; tokenStart[0] = -1; popup.dismiss(); return; }
                tokenStart[0] = m.start(1) - 1; // index of '@'
                final String prefix = m.group(1);
                if (pending[0] != null) MAIN.removeCallbacks(pending[0]);
                pending[0] = () -> {
                    final int my = ++seq[0];
                    MentionUserSearch.search(prefix, results -> {
                        if (my != seq[0] || !et.hasFocus()) return;
                        if (results.isEmpty()) { popup.dismiss(); return; }
                        adapter.set(results.size() > 5 ? results.subList(0, 5) : results);
                        popup.setWidth(et.getWidth());
                        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                        if (!popup.isShowing()) popup.show(); else popup.show();
                    });
                };
                MAIN.postDelayed(pending[0], DEBOUNCE_MS);
            }
        });
        et.setOnFocusChangeListener((v, has) -> { if (!has) popup.dismiss(); });
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
}
