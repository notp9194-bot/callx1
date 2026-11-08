package com.callx.app.compose;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;

import com.callx.app.interactions.StatusForwardBottomSheet;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v750: everything around a template that is not the composer itself —
 *  • owner control   (templateControl/{owner}/{statusId}: reuse on/off, removed)
 *  • save / favourites (savedTemplates/{me}/{owner_statusId}: a snapshot, because the status itself expires in 24h)
 *  • share (link + chat) and report
 *  • the two bottom sheets that expose them (owner / viewer)
 * Every Firebase call is best-effort; a failed read of the control node fails OPEN (template stays usable).
 */
public final class TemplateActions {

    /** https link handled by DeepLinkRouterActivity ("/template/{ownerUid}/{statusId}") → {@link TemplateLinkActivity}. */
    public static final String LINK_BASE = "https://callx-server.onrender.com/template/";

    private static final String[] REPORT_REASONS = {"Spam", "Inappropriate content", "Copyright / copied", "Other"};

    private TemplateActions() { }

    /** Labels shown in the report dialog; {@link #REPORT_REASONS} stay the stable English values written to the database. */
    private static String[] reportLabels(Context c) {
        return new String[]{c.getString(R.string.tpl_act_reason_spam), c.getString(R.string.tpl_act_reason_inappropriate),
                c.getString(R.string.tpl_act_reason_copyright), c.getString(R.string.tpl_act_reason_other)};
    }

    public interface ControlCb { void onResult(boolean reuseOff, boolean removed); }
    public interface BoolCb { void onResult(boolean value); }

    private static boolean ne(@Nullable String s) { return s != null && !s.isEmpty(); }
    private static String me() { return FirebaseUtils.getCurrentUid(); }

    public static String link(String ownerUid, String statusId) { return LINK_BASE + ownerUid + "/" + statusId; }
    public static String savedKey(String ownerUid, String statusId) { return ownerUid + "_" + statusId; }

    // ── owner control ────────────────────────────────────────────────────────

    /** perf: control flags cached 60 s + in-flight dedupe (grid cards, Preview, Status Viewer). Use {@link #fetchControlFresh} where staleness matters. */
    public static void fetchControl(String ownerUid, String statusId, @NonNull final ControlCb cb) {
        if (!ne(ownerUid) || !ne(statusId)) { cb.onResult(false, false); return; }
        final String key = ownerUid + "/" + statusId;
        synchronized (CTRL_LOCK) {
            final long[] hit = CTRL_CACHE.get(key);   // {off, removed, ts}
            if (hit != null && System.currentTimeMillis() - hit[2] < CTRL_TTL_MS) { cb.onResult(hit[0] == 1, hit[1] == 1); return; }
            final List<ControlCb> waiting = CTRL_WAIT.get(key);
            if (waiting != null) { waiting.add(cb); return; }
            final List<ControlCb> l = new ArrayList<>();
            l.add(cb);
            CTRL_WAIT.put(key, l);
        }
        readControl(ownerUid, statusId, (off, removed) -> {
            final List<ControlCb> done;
            synchronized (CTRL_LOCK) {
                CTRL_CACHE.put(key, new long[]{off ? 1 : 0, removed ? 1 : 0, System.currentTimeMillis()});
                done = CTRL_WAIT.remove(key);
            }
            if (done != null) for (ControlCb c : done) c.onResult(off, removed);
        });
    }

    /** Always hits the server (the actual "Use" gate and the owner's switch) and refreshes the cache. */
    public static void fetchControlFresh(final String ownerUid, final String statusId, @NonNull final ControlCb cb) {
        readControl(ownerUid, statusId, (off, removed) -> {
            if (ne(ownerUid) && ne(statusId)) {
                synchronized (CTRL_LOCK) {
                    CTRL_CACHE.put(ownerUid + "/" + statusId, new long[]{off ? 1 : 0, removed ? 1 : 0, System.currentTimeMillis()});
                }
            }
            cb.onResult(off, removed);
        });
    }

    private static void invalidateControl(String ownerUid, String statusId) {
        synchronized (CTRL_LOCK) { CTRL_CACHE.remove(ownerUid + "/" + statusId); }
    }

    private static final long CTRL_TTL_MS = 60_000L;
    private static final Object CTRL_LOCK = new Object();
    private static final Map<String, long[]> CTRL_CACHE = new java.util.LinkedHashMap<String, long[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, long[]> e) { return size() > 300; }
    };
    private static final Map<String, List<ControlCb>> CTRL_WAIT = new HashMap<>();

    private static void readControl(String ownerUid, String statusId, @NonNull final ControlCb cb) {
        try {
            FirebaseUtils.db().getReference("templateControl").child(ownerUid).child(statusId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            cb.onResult(Boolean.TRUE.equals(s.child("off").getValue(Boolean.class)),
                                    Boolean.TRUE.equals(s.child("removed").getValue(Boolean.class)));
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(false, false); }
                    });
        } catch (Exception e) {
            cb.onResult(false, false);
        }
    }

    // ── use ──────────────────────────────────────────────────────────────────

    /**
     * Opens the template composer — after checking the owner has not switched reuse off / removed the template.
     * The owner's own template is never blocked.
     */
    public static void use(@NonNull final Activity a, @Nullable final String json, @Nullable final String stickers,
                           @Nullable final String ownerUid, @Nullable final String statusId, @Nullable final String ownerName,
                           @Nullable final Runnable beforeLaunch) {
        use(a, json, stickers, ownerUid, statusId, ownerName, beforeLaunch, null);
    }

    /** @param afterAny runs once the decision is made (launched, blocked or unavailable) — lets a link screen close itself. */
    public static void use(@NonNull final Activity a, @Nullable final String json, @Nullable final String stickers,
                           @Nullable final String ownerUid, @Nullable final String statusId, @Nullable final String ownerName,
                           @Nullable final Runnable beforeLaunch, @Nullable final Runnable afterAny) {
        if (!ne(json) || !ne(ownerUid) || !ne(statusId)) {
            toast(a, a.getString(R.string.tpl_act_unavailable));
            if (afterAny != null) afterAny.run();
            return;
        }
        final String me = me();
        if (me != null && me.equals(ownerUid)) {
            launchOrRetryHint(a, json, stickers, ownerUid, statusId, ownerName, beforeLaunch);
            if (afterAny != null) afterAny.run();
            return;
        }
        final String key = ownerUid + "/" + statusId;
        if (!USE_IN_FLIGHT.add(key)) return;   // second tap while the owner-switch check is still running → ignore, no double launch
        // feedback: a slow network used to look like a dead button → say what is happening after a short wait
        final Runnable slow = () -> { if (!a.isFinishing() && !a.isDestroyed()) toast(a, a.getString(R.string.tpl_act_checking)); };
        UI.postDelayed(slow, 700L);
        fetchControlFresh(ownerUid, statusId, (off, removed) -> {
            UI.removeCallbacks(slow);
            USE_IN_FLIGHT.remove(key);
            if (a.isFinishing() || a.isDestroyed()) { if (afterAny != null) afterAny.run(); return; }
            if (removed) toast(a, a.getString(R.string.tpl_act_removed));
            else if (off) toast(a, a.getString(R.string.tpl_act_reuse_off));
            else launchOrRetryHint(a, json, stickers, ownerUid, statusId, ownerName, beforeLaunch);
            if (afterAny != null) afterAny.run();
        });
    }

    private static final java.util.Set<String> USE_IN_FLIGHT = new java.util.HashSet<>();
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Launches the composer; when that fails (it used to fail silently) tell the user so the button / link can simply be tapped again. */
    private static void launchOrRetryHint(Activity a, String json, String stickers, String ownerUid, String statusId, String ownerName,
                                          @Nullable Runnable beforeLaunch) {
        if (!launch(a, json, stickers, ownerUid, statusId, ownerName, beforeLaunch)) {
            toast(a, a.getString(R.string.tpl_act_open_failed));
        }
    }

    private static boolean launch(Activity a, String json, String stickers, String ownerUid, String statusId, String ownerName,
                                  @Nullable Runnable beforeLaunch) {
        try {
            final Intent i = new Intent(a, NewStatusActivity.class);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_JSON, json);
            TemplatePayloadStore.put(i, NewStatusActivity.EXTRA_TEMPLATE_STICKERS_JSON, stickers);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_FROM_UID, ownerUid);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_FROM_ID, statusId);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_FROM_NAME, ownerName != null ? ownerName : "");
            a.startActivity(i);
            if (beforeLaunch != null) beforeLaunch.run();   // after a successful start, so "launched" really means launched
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── save / favourites ────────────────────────────────────────────────────

    public static void isSaved(String ownerUid, String statusId, @NonNull final BoolCb cb) {
        final String me = me();
        if (!ne(me) || !ne(ownerUid) || !ne(statusId)) { cb.onResult(false); return; }
        FirebaseUtils.db().getReference("savedTemplates").child(me).child(savedKey(ownerUid, statusId))
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) { cb.onResult(s.exists()); }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(false); }
                });
    }

    /** Stores a snapshot (geometry + the poster's music / text) so it still works after the original status expires. */
    public static boolean save(@Nullable String ownerUid, @Nullable String statusId, @Nullable String ownerName,
                               @Nullable String json, @Nullable String stickers) {
        try {
            final String me = me();
            if (!ne(me) || !ne(ownerUid) || !ne(statusId) || !ne(json) || json.length() > StatusTemplateUses.MAX_JSON) return false;
            final Map<String, Object> m = new HashMap<>();
            m.put("ownerUid", ownerUid);
            m.put("statusId", statusId);
            m.put("ownerName", StatusTemplateUses.capName(ownerName));
            m.put("templateJson", json);
            if (ne(stickers) && stickers.length() <= StatusTemplateUses.MAX_JSON) m.put("stickersJson", stickers);
            m.put("ts", ServerValue.TIMESTAMP);
            FirebaseUtils.db().getReference("savedTemplates").child(me).child(savedKey(ownerUid, statusId)).setValue(m);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void unsave(@Nullable String ownerUid, @Nullable String statusId) {
        try {
            final String me = me();
            if (!ne(me) || !ne(ownerUid) || !ne(statusId)) return;
            FirebaseUtils.db().getReference("savedTemplates").child(me).child(savedKey(ownerUid, statusId)).removeValue();
        } catch (Exception ignored) { }
    }

    // ── latest version (v788, item 21) ───────────────────────────────────────

    public interface LatestCb { void onResult(@Nullable String templateJson, @Nullable String stickersJson); }

    private static final long LATEST_TTL_MS = 120_000L;
    private static final Object LATEST_LOCK = new Object();
    private static final Map<String, String[]> LATEST_CACHE = new java.util.LinkedHashMap<String, String[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String[]> e) { return size() > 200; }
    };   // key -> {json, stickers, ts}

    /**
     * The owner's newest version of a template (templateLatest/{owner}/{id}); it outlives the 24h status, so a saved snapshot
     * can follow the owner's edits. Cached 2 min. Calls back with (null, null) when there is none / the read failed.
     * When {@code refreshMySnapshot} is true and the newest version differs from {@code currentJson}, my own saved snapshot
     * (savedTemplates/{me}/…, my own node) is rewritten too, so the next open is already fresh.
     */
    public static void fetchLatest(@Nullable final String ownerUid, @Nullable final String statusId,
                                   @Nullable final String currentJson, final boolean refreshMySnapshot, @NonNull final LatestCb cb) {
        if (!ne(ownerUid) || !ne(statusId)) { cb.onResult(null, null); return; }
        final String key = ownerUid + "/" + statusId;
        synchronized (LATEST_LOCK) {
            final String[] hit = LATEST_CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - Long.parseLong(hit[2]) < LATEST_TTL_MS) {
                cb.onResult(hit[0], hit[1]);
                return;
            }
        }
        try {
            FirebaseUtils.db().getReference("templateLatest").child(ownerUid).child(statusId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            final String json = s.child("templateJson").getValue(String.class);
                            final String st = s.child("stickersJson").getValue(String.class);
                            if (!ne(json) || json.length() > StatusTemplateUses.MAX_JSON) { cb.onResult(null, null); return; }
                            synchronized (LATEST_LOCK) {
                                LATEST_CACHE.put(key, new String[]{json, st, String.valueOf(System.currentTimeMillis())});
                            }
                            if (refreshMySnapshot && !json.equals(currentJson)) refreshSnapshot(ownerUid, statusId, json, st);
                            cb.onResult(json, st);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(null, null); }
                    });
        } catch (Exception e) {
            cb.onResult(null, null);
        }
    }

    /** Drops the cached newest version (owner just edited it). */
    public static void invalidateLatest(@Nullable String ownerUid, @Nullable String statusId) {
        if (!ne(ownerUid) || !ne(statusId)) return;
        synchronized (LATEST_LOCK) { LATEST_CACHE.remove(ownerUid + "/" + statusId); }
    }

    /** Rewrites ONLY the geometry (+ stickers) of my own saved snapshot, and only if it still exists (never re-creates an unsaved one). */
    private static void refreshSnapshot(final String ownerUid, final String statusId, final String json, @Nullable final String stickers) {
        try {
            final String me = me();
            if (!ne(me)) return;
            final com.google.firebase.database.DatabaseReference ref = FirebaseUtils.db().getReference("savedTemplates")
                    .child(me).child(savedKey(ownerUid, statusId));
            ref.child("ownerUid").addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (!s.exists()) return;   // un-saved meanwhile
                    final Map<String, Object> up = new HashMap<>();
                    up.put("templateJson", json);
                    if (ne(stickers)) up.put("stickersJson", stickers);
                    ref.updateChildren(up);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            });
        } catch (Exception ignored) { }
    }

    // ── share / report ───────────────────────────────────────────────────────

    public static void shareLink(@NonNull Context c, String ownerUid, String statusId) {
        try {
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, c.getString(R.string.tpl_act_share_text) + link(ownerUid, statusId));
            c.startActivity(Intent.createChooser(send, c.getString(R.string.tpl_act_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    public static void copyLink(@NonNull Context c, String ownerUid, String statusId) {
        try {
            final ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("template", link(ownerUid, statusId)));
            Toast.makeText(c, c.getString(R.string.tpl_act_link_copied), Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    public static void report(@NonNull final Activity a, final String ownerUid, final String statusId, @Nullable final Runnable onClosed) {
        final String me = me();
        if (!ne(me) || !ne(ownerUid) || !ne(statusId) || me.equals(ownerUid)) return;
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.tpl_act_report_title))
                .setItems(reportLabels(a), (dlg, which) -> {
                    try {
                        final Map<String, Object> m = new HashMap<>();
                        m.put("reason", REPORT_REASONS[which]);
                        m.put("ts", ServerValue.TIMESTAMP);
                        // v752: toast follows the real result (rules allow ONE report per user per template)
                        FirebaseUtils.db().getReference("templateReports").child(ownerUid).child(statusId).child(me).setValue(m, (err, ref) ->
                                toast(a, err == null ? a.getString(R.string.tpl_act_report_sent)
                                        : err.getCode() == DatabaseError.PERMISSION_DENIED ? a.getString(R.string.tpl_act_report_dup)
                                        : a.getString(R.string.tpl_act_report_failed)));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton(a.getString(R.string.tpl_act_cancel), null)
                .create();
        d.setOnDismissListener(x -> { if (onClosed != null) onClosed.run(); });
        d.show();
    }

    // ── sheets ───────────────────────────────────────────────────────────────

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    private static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    private static boolean isDark(Context c) {
        final TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        return ColorUtils.calculateLuminance(tv.data) < 0.5;
    }

    private static LinearLayout sheetRoot(Context c, String title, @Nullable String sub) {
        final boolean dark = isDark(c);
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 24));
        final TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(18f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(dark ? 0xFFFFFFFF : 0xFF111111);
        root.addView(t);
        if (sub != null) {
            final TextView s = new TextView(c);
            s.setText(sub);
            s.setTextSize(13f);
            s.setTextColor(dark ? 0xAAFFFFFF : 0x99000000);
            s.setPadding(0, dp(c, 2), 0, 0);
            root.addView(s);
        }
        return root;
    }

    private static TextView row(Context c, LinearLayout root, String text, boolean danger, View.OnClickListener click) {
        final boolean dark = isDark(c);
        final TextView r = new TextView(c);
        r.setText(text);
        r.setTextSize(16f);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTextColor(danger ? 0xFFE5484D : dark ? 0xFFFFFFFF : 0xFF111111);
        r.setPadding(0, dp(c, 14), 0, dp(c, 14));
        r.setOnClickListener(click);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(r, lp);
        return r;
    }

    private static BottomSheetDialog show(Activity a, LinearLayout root, @Nullable Runnable onPause, @Nullable Runnable onResume) {
        final BottomSheetDialog sheet = new BottomSheetDialog(a);
        sheet.setContentView(root);
        if (onPause != null) onPause.run();
        sheet.setOnDismissListener(d -> { if (onResume != null) onResume.run(); });
        sheet.show();
        return sheet;
    }

    /** Owner's menu on his own template: who used it, reuse on / off, link, remove. */
    public static void showOwnerSheet(@NonNull final Activity a, @NonNull final String statusId, final long uses,
                                      @Nullable final Runnable onPause, @Nullable final Runnable onResume,
                                      @Nullable final Runnable onRemoved) {
        showOwnerSheet(a, statusId, uses, onPause, onResume, onRemoved, null);
    }

    /** @param onEdit when not null the sheet starts with "Template edit karo" (opens the Template Editor, v772). */
    public static void showOwnerSheet(@NonNull final Activity a, @NonNull final String statusId, final long uses,
                                      @Nullable final Runnable onPause, @Nullable final Runnable onResume,
                                      @Nullable final Runnable onRemoved, @Nullable final Runnable onEdit) {
        final String me = me();
        if (!ne(me)) return;
        final LinearLayout root = sheetRoot(a, a.getString(R.string.tpl_act_owner_title), uses > 0 ? uses + " logon ne use kiya" : "Abhi kisi ne use nahi kiya");
        final BottomSheetDialog[] ref = new BottomSheetDialog[1];

        if (onEdit != null) row(a, root, a.getString(R.string.mt_menu_edit), false, v -> {
            ref[0].dismiss();
            onEdit.run();
        });

        row(a, root, a.getString(R.string.tpl_act_who_used), false, v -> {
            ref[0].dismiss();
            a.startActivity(new Intent(a, TemplateUsesActivity.class).putExtra(TemplateUsesActivity.EXTRA_STATUS_ID, statusId));
        });

        final SwitchCompat sw = new SwitchCompat(a);
        sw.setText(a.getString(R.string.tpl_act_allow_reuse));
        sw.setTextSize(16f);
        sw.setTextColor(isDark(a) ? 0xFFFFFFFF : 0xFF111111);
        sw.setPadding(0, dp(a, 14), 0, dp(a, 14));
        sw.setChecked(true);
        sw.setEnabled(false);                       // until the saved state is known
        root.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fetchControlFresh(me, statusId, (off, removed) -> {
            sw.setChecked(!off);
            sw.setEnabled(true);
            sw.setOnCheckedChangeListener((b, on) -> {
                try {
                    final Map<String, Object> m = new HashMap<>();
                    m.put("off", on ? null : Boolean.TRUE);
                    m.put("ts", ServerValue.TIMESTAMP);
                    FirebaseUtils.db().getReference("templateControl").child(me).child(statusId).updateChildren(m);
                    invalidateControl(me, statusId);
                    toast(a, on ? a.getString(R.string.tpl_act_reuse_on_toast) : a.getString(R.string.tpl_act_reuse_off_toast));
                } catch (Exception ignored) { }
            });
        });

        row(a, root, a.getString(R.string.tpl_act_link_share), false, v -> shareLink(a, me, statusId));
        row(a, root, a.getString(R.string.tpl_act_link_copy), false, v -> copyLink(a, me, statusId));
        row(a, root, a.getString(R.string.tpl_act_remove), true, v -> {
            ref[0].dismiss();
            new AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.tpl_act_remove_title))
                    .setMessage(a.getString(R.string.tpl_act_remove_msg))
                    .setPositiveButton(a.getString(R.string.tpl_act_remove_confirm), (d, w) -> removeTemplate(a, statusId, onRemoved))
                    .setNegativeButton(a.getString(R.string.tpl_act_cancel), null)
                    .show();
        });
        ref[0] = show(a, root, onPause, onResume);
    }

    private static void removeTemplate(Activity a, String statusId, @Nullable Runnable onRemoved) {
        try {
            final String me = me();
            if (!ne(me)) return;
            final Map<String, Object> m = new HashMap<>();
            m.put("removed", Boolean.TRUE);
            m.put("off", Boolean.TRUE);
            m.put("ts", ServerValue.TIMESTAMP);
            FirebaseUtils.db().getReference("templateControl").child(me).child(statusId).updateChildren(m);
                    invalidateControl(me, statusId);
            FirebaseUtils.db().getReference("status").child(me).child(statusId).child("templateJson").removeValue();
            toast(a, a.getString(R.string.tpl_act_removed_toast));
            if (onRemoved != null) onRemoved.run();
        } catch (Exception ignored) { }
    }

    /** Viewer's menu on somebody's template: save, send in chat, link, report. */
    public static void showViewerSheet(@NonNull final Activity a, @NonNull final StatusItem item,
                                       @NonNull final String originUid, @NonNull final String originId,
                                       @Nullable final String originName,
                                       @Nullable final Runnable onPause, @Nullable final Runnable onResume) {
        final LinearLayout root = sheetRoot(a, a.getString(R.string.tpl_act_viewer_title), originName != null && !originName.isEmpty() ? "@" + originName : null);
        final BottomSheetDialog[] ref = new BottomSheetDialog[1];

        final TextView saveRow = row(a, root, a.getString(R.string.tpl_act_save_row), false, null);
        final boolean[] saved = {false};
        isSaved(originUid, originId, s -> {
            saved[0] = s;
            saveRow.setText(s ? a.getString(R.string.tpl_act_unsave_row) : a.getString(R.string.tpl_act_save_row));
        });
        saveRow.setOnClickListener(v -> {
            if (saved[0]) { unsave(originUid, originId); toast(a, a.getString(R.string.tpl_act_unsaved_toast)); }
            else if (save(originUid, originId, originName, item.templateJson, item.stickersJson)) toast(a, a.getString(R.string.tpl_act_saved_toast));
            else toast(a, a.getString(R.string.tpl_act_save_failed));
            ref[0].dismiss();
        });
        row(a, root, a.getString(R.string.tpl_act_send_chat), false, v -> {
            ref[0].dismiss();
            final String me = me();
            if (onPause != null) onPause.run();   // the sheet's dismiss just resumed the story
            if (ne(me)) StatusForwardBottomSheet.show(a, item, me, onResume);
        });
        row(a, root, a.getString(R.string.tpl_act_link_share), false, v -> shareLink(a, originUid, originId));
        row(a, root, a.getString(R.string.tpl_act_link_copy), false, v -> copyLink(a, originUid, originId));
        final String me = me();
        if (me != null && !me.equals(originUid)) {
            row(a, root, a.getString(R.string.tpl_act_report), true, v -> {
                ref[0].dismiss();
                if (onPause != null) onPause.run();
                report(a, originUid, originId, onResume);
            });
        }
        ref[0] = show(a, root, onPause, onResume);
    }
}
