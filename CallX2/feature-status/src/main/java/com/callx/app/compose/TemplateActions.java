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
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
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

    public interface ControlCb { void onResult(boolean reuseOff, boolean removed); }
    public interface BoolCb { void onResult(boolean value); }

    private static boolean ne(@Nullable String s) { return s != null && !s.isEmpty(); }
    private static String me() { return FirebaseUtils.getCurrentUid(); }

    public static String link(String ownerUid, String statusId) { return LINK_BASE + ownerUid + "/" + statusId; }
    public static String savedKey(String ownerUid, String statusId) { return ownerUid + "_" + statusId; }

    // ── owner control ────────────────────────────────────────────────────────

    public static void fetchControl(String ownerUid, String statusId, @NonNull final ControlCb cb) {
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
            toast(a, "Template ab available nahi hai");
            if (afterAny != null) afterAny.run();
            return;
        }
        final String me = me();
        if (me != null && me.equals(ownerUid)) {
            launch(a, json, stickers, ownerUid, statusId, ownerName, beforeLaunch);
            if (afterAny != null) afterAny.run();
            return;
        }
        fetchControl(ownerUid, statusId, (off, removed) -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (removed) toast(a, "Poster ne ye template hata diya hai");
            else if (off) toast(a, "Poster ne is template ka reuse band kiya hai");
            else launch(a, json, stickers, ownerUid, statusId, ownerName, beforeLaunch);
            if (afterAny != null) afterAny.run();
        });
    }

    private static void launch(Activity a, String json, String stickers, String ownerUid, String statusId, String ownerName,
                               @Nullable Runnable beforeLaunch) {
        try {
            final Intent i = new Intent(a, NewStatusActivity.class);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_JSON, json);
            if (ne(stickers) && stickers.length() < 100_000) i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_STICKERS_JSON, stickers);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_FROM_UID, ownerUid);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_FROM_ID, statusId);
            i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_FROM_NAME, ownerName != null ? ownerName : "");
            if (beforeLaunch != null) beforeLaunch.run();
            a.startActivity(i);
        } catch (Exception ignored) { }
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

    // ── share / report ───────────────────────────────────────────────────────

    public static void shareLink(@NonNull Context c, String ownerUid, String statusId) {
        try {
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, "Is template se apna CallX status banao ✨\n" + link(ownerUid, statusId));
            c.startActivity(Intent.createChooser(send, "Template share karo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    public static void copyLink(@NonNull Context c, String ownerUid, String statusId) {
        try {
            final ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("template", link(ownerUid, statusId)));
            Toast.makeText(c, "Link copy ho gaya", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    public static void report(@NonNull final Activity a, final String ownerUid, final String statusId, @Nullable final Runnable onClosed) {
        final String me = me();
        if (!ne(me) || !ne(ownerUid) || !ne(statusId) || me.equals(ownerUid)) return;
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Template report karo")
                .setItems(REPORT_REASONS, (dlg, which) -> {
                    try {
                        final Map<String, Object> m = new HashMap<>();
                        m.put("reason", REPORT_REASONS[which]);
                        m.put("ts", ServerValue.TIMESTAMP);
                        // v752: toast follows the real result (rules allow ONE report per user per template)
                        FirebaseUtils.db().getReference("templateReports").child(ownerUid).child(statusId).child(me).setValue(m, (err, ref) ->
                                toast(a, err == null ? "Report bhej diya, shukriya"
                                        : err.getCode() == DatabaseError.PERMISSION_DENIED ? "Aap is template ko pehle hi report kar chuke ho"
                                        : "Report nahi gaya, dobara try karo"));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("Cancel", null)
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
        final String me = me();
        if (!ne(me)) return;
        final LinearLayout root = sheetRoot(a, "Your template", uses > 0 ? uses + " logon ne use kiya" : "Abhi kisi ne use nahi kiya");
        final BottomSheetDialog[] ref = new BottomSheetDialog[1];

        row(a, root, "Kisne use kiya", false, v -> {
            ref[0].dismiss();
            a.startActivity(new Intent(a, TemplateUsesActivity.class).putExtra(TemplateUsesActivity.EXTRA_STATUS_ID, statusId));
        });

        final SwitchCompat sw = new SwitchCompat(a);
        sw.setText("Doosre is template ko use kar sakte hain");
        sw.setTextSize(16f);
        sw.setTextColor(isDark(a) ? 0xFFFFFFFF : 0xFF111111);
        sw.setPadding(0, dp(a, 14), 0, dp(a, 14));
        sw.setChecked(true);
        sw.setEnabled(false);                       // until the saved state is known
        root.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fetchControl(me, statusId, (off, removed) -> {
            sw.setChecked(!off);
            sw.setEnabled(true);
            sw.setOnCheckedChangeListener((b, on) -> {
                try {
                    final Map<String, Object> m = new HashMap<>();
                    m.put("off", on ? null : Boolean.TRUE);
                    m.put("ts", ServerValue.TIMESTAMP);
                    FirebaseUtils.db().getReference("templateControl").child(me).child(statusId).updateChildren(m);
                    toast(a, on ? "Reuse on" : "Reuse band — naye log use nahi kar payenge");
                } catch (Exception ignored) { }
            });
        });

        row(a, root, "Link share karo", false, v -> shareLink(a, me, statusId));
        row(a, root, "Link copy karo", false, v -> copyLink(a, me, statusId));
        row(a, root, "Template hatao", true, v -> {
            ref[0].dismiss();
            new AlertDialog.Builder(a)
                    .setTitle("Template hatao?")
                    .setMessage("Status rahega, par log ise template ki tarah use nahi kar payenge. Saved copies bhi band ho jayengi.")
                    .setPositiveButton("Hatao", (d, w) -> removeTemplate(a, statusId, onRemoved))
                    .setNegativeButton("Cancel", null)
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
            FirebaseUtils.db().getReference("status").child(me).child(statusId).child("templateJson").removeValue();
            toast(a, "Template hata diya");
            if (onRemoved != null) onRemoved.run();
        } catch (Exception ignored) { }
    }

    /** Viewer's menu on somebody's template: save, send in chat, link, report. */
    public static void showViewerSheet(@NonNull final Activity a, @NonNull final StatusItem item,
                                       @NonNull final String originUid, @NonNull final String originId,
                                       @Nullable final String originName,
                                       @Nullable final Runnable onPause, @Nullable final Runnable onResume) {
        final LinearLayout root = sheetRoot(a, "Template", originName != null && !originName.isEmpty() ? "@" + originName : null);
        final BottomSheetDialog[] ref = new BottomSheetDialog[1];

        final TextView saveRow = row(a, root, "♡  Template save karo", false, null);
        final boolean[] saved = {false};
        isSaved(originUid, originId, s -> {
            saved[0] = s;
            saveRow.setText(s ? "♥  Saved — hatao" : "♡  Template save karo");
        });
        saveRow.setOnClickListener(v -> {
            if (saved[0]) { unsave(originUid, originId); toast(a, "Saved se hata diya"); }
            else if (save(originUid, originId, originName, item.templateJson, item.stickersJson)) toast(a, "Template save ho gaya");
            else toast(a, "Ye template save nahi ho paya");
            ref[0].dismiss();
        });
        row(a, root, "Chat me bhejo", false, v -> {
            ref[0].dismiss();
            final String me = me();
            if (onPause != null) onPause.run();   // the sheet's dismiss just resumed the story
            if (ne(me)) StatusForwardBottomSheet.show(a, item, me, onResume);
        });
        row(a, root, "Link share karo", false, v -> shareLink(a, originUid, originId));
        row(a, root, "Link copy karo", false, v -> copyLink(a, originUid, originId));
        final String me = me();
        if (me != null && !me.equals(originUid)) {
            row(a, root, "Report", true, v -> {
                ref[0].dismiss();
                if (onPause != null) onPause.run();
                report(a, originUid, originId, onResume);
            });
        }
        ref[0] = show(a, root, onPause, onResume);
    }
}
