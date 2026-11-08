package com.callx.app.compose;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;

import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * v769 — Screen 2 "Template Preview": Discovery -> Preview -> Use Template.
 *
 *  - Dark stage with the animated template preview (boxes light up one after another, tap = pause / play),
 *    back + save (bookmark) buttons, duration and music chips.
 *  - White rounded sheet: title, "N uses", creator row with Follow, "What's inside" tiles
 *    (Music / Photos / Videos / Duration — read from templateJson + stickersJson, nothing invented), Use Template.
 *  - Premium iOS look (white surface, purple accent, rounded cards); every icon is an existing project drawable.
 *
 * The owner's reuse switch is still enforced by {@link TemplateActions#use} when the button is tapped.
 * The Save state is handed back to the caller through the activity result ({@link #RESULT_SAVED}).
 */
public class TemplatePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_TPL_JSON = "tplJson";
    public static final String EXTRA_STICKERS_JSON = "tplStickers";
    public static final String EXTRA_OWNER_UID = "tplOwnerUid";
    public static final String EXTRA_STATUS_ID = "tplStatusId";
    public static final String EXTRA_OWNER_NAME = "tplOwnerName";
    public static final String EXTRA_USES = "tplUses";          // long, -1 = unknown
    public static final String EXTRA_SAVED = "tplSaved";        // boolean, state when opened
    /** Result extra: the final saved state (boolean) — only set when the user toggled it. */
    public static final String RESULT_SAVED = "tplSavedNow";

    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF;
    private static final long LOOP_MS_PER_BOX = 1100L;

    private String json, stickers, ownerUid, statusId, ownerName;
    private long uses;
    private boolean mine, saved, savedChanged, following, followBusy, launched, playing = true;

    private boolean dark;
    private int bg, surface, textColor, subColor;

    private TemplatePreviewDrawable preview;
    private ValueAnimator loop;
    private View playBadge;
    private ImageView saveIcon;
    private TextView usesText, followBtn, avatarInitial;
    private ImageView avatar;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Intent in = getIntent();
        json = in.getStringExtra(EXTRA_TPL_JSON);
        stickers = in.getStringExtra(EXTRA_STICKERS_JSON);
        ownerUid = in.getStringExtra(EXTRA_OWNER_UID);
        statusId = in.getStringExtra(EXTRA_STATUS_ID);
        ownerName = in.getStringExtra(EXTRA_OWNER_NAME);
        uses = in.getLongExtra(EXTRA_USES, -1L);
        saved = in.getBooleanExtra(EXTRA_SAVED, false);
        preview = TemplatePreviewDrawable.fromJson(json);
        final String me = FirebaseUtils.getCurrentUid();
        mine = ne(me) && me.equals(ownerUid);
        if (preview == null || !ne(ownerUid) || !ne(statusId)) {
            Toast.makeText(this, "Template ab available nahi hai", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        bg = tv.data;
        dark = ColorUtils.calculateLuminance(bg) < 0.5;
        surface = dark ? 0x22FFFFFF : 0xFFF2F0F8;
        textColor = dark ? 0xFFFFFFFF : 0xFF14142B;
        subColor = dark ? 0xAAFFFFFF : 0xFF8A8AA3;

        final TemplateSpec spec = TemplateSpec.parse(json);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0816);
        root.addView(buildStage(spec), new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildSheet(spec), new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        // dark stage reaches the status bar -> match it, light icons; nav bar follows the white / dark sheet
        getWindow().setStatusBarColor(0xFF1E1540);
        getWindow().setNavigationBarColor(bg);
        final androidx.core.view.WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), root);
        wic.setAppearanceLightStatusBars(false);
        wic.setAppearanceLightNavigationBars(!dark);

        refreshUses();
        if (!mine) { loadCreator(); loadFollowState(); }
        startLoop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (playing) startLoop();
    }

    @Override protected void onPause() {
        stopLoop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopLoop();
        super.onDestroy();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean ne(@Nullable String s) { return s != null && !s.isEmpty(); }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable oval(int color) {
        final GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private ImageView icon(@DrawableRes int res, int tint, int sizeDp) {
        final ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageViewCompat.setImageTintList(v, ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private TextView text(CharSequence s, float sp, int color, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void clipRound(View v, final int radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp)); }
        });
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return trimZero(n / 100_000 / 10.0) + "M";
        if (n >= 1_000) return trimZero(n / 100 / 10.0) + "K";
        return String.valueOf(n);
    }

    private static String trimZero(double d) {
        final String s = String.format(Locale.US, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private FrameLayout circleButton(@DrawableRes int res, int tint, int cd) {
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(oval(0x66000000));
        f.addView(icon(res, tint, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        f.setContentDescription(getString(cd));
        return f;
    }

    // ── stage (dark, animated preview) ───────────────────────────────────────

    private View buildStage(TemplateSpec spec) {
        final FrameLayout stage = new FrameLayout(this);
        stage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1E1540, 0xFF0B0816}));

        // 9:16 card, as large as the stage allows
        final FrameLayout holder = new FrameLayout(this) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                final int aw = MeasureSpec.getSize(wSpec), ah = MeasureSpec.getSize(hSpec);
                int h = ah, w = Math.round(h * TemplatePreviewDrawable.ASPECT);
                if (w > aw) { w = aw; h = Math.round(w / TemplatePreviewDrawable.ASPECT); }
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        };
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(round(0xFFFFFFFF, 26));
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.setElevation(dp(10));
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageDrawable(preview);
        clipRound(img, 23);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        holder.addView(card, new FrameLayout.LayoutParams(-1, -1));

        playBadge = new FrameLayout(this);
        final GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.OVAL);
        pg.setColor(0x80000000);
        pg.setStroke(dp(2), 0xFFFFFFFF);
        playBadge.setBackground(pg);
        ((FrameLayout) playBadge).addView(icon(R.drawable.ic_status_card_play, 0xFFFFFFFF, 30),
                new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        playBadge.setVisibility(View.GONE);
        holder.addView(playBadge, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        // chips on the card: duration (left) + music (right)
        final int ms = spec.displayMs();
        if (ms > 0) {
            final LinearLayout chip = chip(R.drawable.ic_timer, TemplateSpec.formatDuration(ms));
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            lp.setMargins(dp(12), 0, 0, dp(12));
            holder.addView(chip, lp);
        }
        final String song = musicName();
        if (song != null) {
            final LinearLayout chip = chip(R.drawable.ic_music_note, song.isEmpty() ? getString(R.string.tpl_prev_music) : song);
            ((TextView) chip.getChildAt(1)).setMaxWidth(dp(110));
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, dp(12), dp(12));
            holder.addView(chip, lp);
        }
        holder.setOnClickListener(v -> togglePlay());
        holder.setContentDescription(getString(R.string.tpl_prev_play_pause));
        final FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        hlp.setMargins(dp(40), dp(64), dp(40), dp(24));
        stage.addView(holder, hlp);

        // top bar buttons
        final FrameLayout back = circleButton(R.drawable.ic_arrow_back, 0xFFFFFFFF, R.string.tpl_prev_back);
        back.setOnClickListener(v -> finish());
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.START);
        blp.setMargins(dp(14), dp(12), 0, 0);
        stage.addView(back, blp);

        final FrameLayout save = new FrameLayout(this);
        save.setBackground(oval(0x66000000));
        saveIcon = icon(saved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark, 0xFFFFFFFF, 22);
        save.addView(saveIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        save.setContentDescription(getString(R.string.tpl_prev_save));
        save.setOnClickListener(v -> toggleSave());
        final FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.END);
        slp.setMargins(0, dp(12), dp(14), 0);
        stage.addView(save, slp);
        return stage;
    }

    private LinearLayout chip(@DrawableRes int res, String label) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(8), dp(5), dp(10), dp(5));
        c.setBackground(round(0x99000000, 14));
        c.addView(icon(res, 0xFFFFFFFF, 14));
        final TextView t = text(label, 11f, 0xFFFFFFFF, true);
        t.setPadding(dp(4), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t);
        return c;
    }

    // ── sheet (white, rounded) ───────────────────────────────────────────────

    private View buildSheet(TemplateSpec spec) {
        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(18), dp(20), dp(16));
        final GradientDrawable sg = new GradientDrawable();
        sg.setColor(bg);
        final float r = dp(28);
        sg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sg);

        // title + uses pill
        final LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        final String who = ne(ownerName) ? ownerName : "user";
        final TextView title = text(mine ? getString(R.string.tpl_prev_title_mine) : getString(R.string.tpl_prev_title_other, who),
                20f, textColor, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final LinearLayout usesPill = new LinearLayout(this);
        usesPill.setOrientation(LinearLayout.HORIZONTAL);
        usesPill.setGravity(Gravity.CENTER_VERTICAL);
        usesPill.addView(icon(R.drawable.ic_sound_flame_outline, 0xFFFF6A1A, 18));
        usesText = text("", 14f, 0xFFFF6A1A, true);
        usesText.setPadding(dp(4), 0, 0, 0);
        usesPill.addView(usesText);
        titleRow.addView(usesPill, new LinearLayout.LayoutParams(-2, -2));
        sheet.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        // creator row
        final LinearLayout creator = new LinearLayout(this);
        creator.setOrientation(LinearLayout.HORIZONTAL);
        creator.setGravity(Gravity.CENTER_VERTICAL);
        creator.setPadding(0, dp(16), 0, dp(4));
        final FrameLayout av = new FrameLayout(this);
        av.setBackground(oval(PURPLE));
        clipRound(av, 22);
        av.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        avatarInitial = text(who.substring(0, 1).toUpperCase(Locale.ROOT), 17f, 0xFFFFFFFF, true);
        av.addView(avatarInitial, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setVisibility(View.GONE);
        av.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        creator.addView(av, new LinearLayout.LayoutParams(dp(44), dp(44)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        final TextView nm = text(mine ? getString(R.string.tpl_prev_you) : who, 15f, textColor, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nm);
        final TextView sub = text(getString(R.string.tpl_prev_creator), 12f, subColor, false);
        sub.setPadding(0, dp(2), 0, 0);
        names.addView(sub);
        final LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.setMargins(dp(12), 0, dp(8), 0);
        creator.addView(names, nlp);
        followBtn = text("", 13f, 0xFFFFFFFF, true);
        followBtn.setGravity(Gravity.CENTER);
        followBtn.setPadding(dp(20), dp(9), dp(20), dp(9));
        followBtn.setOnClickListener(v -> toggleFollow());
        followBtn.setVisibility(mine ? View.GONE : View.VISIBLE);
        styleFollow();
        creator.addView(followBtn, new LinearLayout.LayoutParams(-2, -2));
        sheet.addView(creator, new LinearLayout.LayoutParams(-1, -2));

        // what's inside
        final TextView head = text(getString(R.string.tpl_prev_details), 16f, textColor, true);
        head.setPadding(0, dp(18), 0, dp(10));
        sheet.addView(head);

        final int videos = countVideos(spec);
        int photosAll = preview.photoCount();
        if (spec.isMultiScene()) {   // v774: every scene's boxes count (base + boxes of each scene)
            photosAll = 0;
            for (TemplateSpec.Scene sc : spec.scenes) photosAll += sc.slots.size();
        }
        final int photos = Math.max(0, photosAll - videos);
        final String song = musicName();
        final int ms = spec.displayMs();
        final LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(tile(R.drawable.ic_music_note, R.string.tpl_prev_music,
                getString(song != null ? R.string.tpl_prev_yes : R.string.tpl_prev_no)), tileLp(false));
        tiles.addView(tile(R.drawable.ic_photo_library, R.string.tpl_prev_photos, String.valueOf(photos)), tileLp(false));
        tiles.addView(tile(R.drawable.ic_video, R.string.tpl_prev_videos, videos > 0 ? String.valueOf(videos) : "—"), tileLp(false));
        tiles.addView(tile(R.drawable.ic_timer, R.string.tpl_prev_duration,
                ms > 0 ? TemplateSpec.formatDuration(ms) : getString(R.string.tpl_prev_auto)), tileLp(true));
        sheet.addView(tiles, new LinearLayout.LayoutParams(-1, -2));

        // CTA
        final TextView cta = text(getString(R.string.tpl_prev_use), 16f, 0xFFFFFFFF, true);
        cta.setGravity(Gravity.CENTER);
        final GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_LIGHT, PURPLE});
        cg.setCornerRadius(dp(27));
        cta.setBackground(cg);
        cta.setOnClickListener(v -> useTemplate());
        final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(54));
        clp.setMargins(0, dp(20), 0, 0);
        sheet.addView(cta, clp);
        return sheet;
    }

    private LinearLayout.LayoutParams tileLp(boolean last) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(0, 0, last ? 0 : dp(8), 0);
        return lp;
    }

    private View tile(@DrawableRes int res, int label, String value) {
        final LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        t.setBackground(round(surface, 16));
        final FrameLayout ic = new FrameLayout(this);
        ic.setBackground(oval(dark ? 0x336A3DE8 : 0x1A6A3DE8));
        ic.addView(icon(res, PURPLE, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        t.addView(ic, new LinearLayout.LayoutParams(dp(38), dp(38)));
        final TextView v = text(value, 14f, textColor, true);
        v.setPadding(0, dp(8), 0, 0);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(v);
        final TextView l = text(getString(label), 11f, subColor, false);
        l.setPadding(0, dp(2), 0, 0);
        t.addView(l);
        return t;
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private static int countVideos(TemplateSpec spec) {
        int n = 0;
        if (spec.isMultiScene()) {
            for (TemplateSpec.Scene sc : spec.scenes) for (TemplateSpec.Slot s : sc.slots) if (!s.base && s.isVideo()) n++;
            return n;
        }
        for (TemplateSpec.Slot s : spec.slots) if (!s.base && s.isVideo()) n++;
        return n;
    }

    /** null = no music in the template; "" = music without a readable title; else the song title. */
    @Nullable private String musicName() {
        if (!ne(stickers)) return null;
        try {
            final JSONArray a = new JSONArray(stickers);
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.optJSONObject(i);
                if (o != null && "music".equals(o.optString("type"))) {
                    for (String k : new String[]{"song", "musicSong", "title", "name"}) {
                        final String s = o.optString(k, "");
                        if (!s.isEmpty() && !"null".equals(s)) return s;
                    }
                    return "";
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void refreshUses() {
        bindUses(uses);
        StatusTemplateUses.fetchCount(ownerUid, statusId, n -> {
            if (isFinishing() || isDestroyed()) return;
            uses = n;
            bindUses(n);
        });
    }

    private void bindUses(long n) {
        if (usesText == null) return;
        usesText.setText(n > 0 ? getString(R.string.tpl_prev_uses, compact(n)) : getString(R.string.tpl_prev_uses_none));
    }

    private void loadCreator() {
        try {
            FirebaseUtils.db().getReference("users").child(ownerUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            if (isFinishing() || isDestroyed()) return;
                            String photo = s.child("photoUrl").getValue(String.class);
                            if (!ne(photo)) photo = s.child("thumbUrl").getValue(String.class);
                            if (ne(photo)) {
                                avatar.setVisibility(View.VISIBLE);
                                avatarInitial.setVisibility(View.GONE);
                                Glide.with(TemplatePreviewActivity.this).load(photo).override(dp(88), dp(88)).centerCrop().into(avatar);
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { /* initial letter stays */ }
                    });
        } catch (Exception ignored) { }
    }

    private void loadFollowState() {
        final String me = FirebaseUtils.getCurrentUid();
        if (!ne(me)) { followBtn.setVisibility(View.GONE); return; }
        try {
            FirebaseUtils.getReelFollowsRef(me).child(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (isFinishing() || isDestroyed() || followBusy) return;
                    following = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    styleFollow();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { /* stays "Follow" */ }
            });
        } catch (Exception ignored) { }
    }

    private void styleFollow() {
        if (followBtn == null) return;
        followBtn.setText(following ? R.string.tpl_prev_following : R.string.tpl_prev_follow);
        followBtn.setTextColor(following ? textColor : 0xFFFFFFFF);
        if (following) {
            final GradientDrawable g = round(surface, 20);
            followBtn.setBackground(g);
        } else {
            followBtn.setBackground(round(PURPLE, 20));
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private void toggleFollow() {
        final String me = FirebaseUtils.getCurrentUid();
        if (!ne(me) || mine || followBusy) return;
        final boolean target = !following;
        followBusy = true;
        following = target;
        styleFollow();
        FirebaseUtils.setFollowState(me, ownerUid, target, (err, ref) -> {
            followBusy = false;
            if (err != null && !isFinishing() && !isDestroyed()) {
                following = !target;                       // the write did not land -> revert
                styleFollow();
                Toast.makeText(this, R.string.tpl_prev_follow_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void toggleSave() {
        if (saved) {
            TemplateActions.unsave(ownerUid, statusId);
            saved = false;
            Toast.makeText(this, R.string.tpl_prev_unsaved_toast, Toast.LENGTH_SHORT).show();
        } else if (TemplateActions.save(ownerUid, statusId, ownerName, json, stickers)) {
            saved = true;
            Toast.makeText(this, R.string.tpl_prev_saved_toast, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.tpl_prev_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        savedChanged = true;
        saveIcon.setImageResource(saved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        setResult(RESULT_OK, new Intent().putExtra(RESULT_SAVED, saved));
    }

    private void useTemplate() {
        // re-checks the owner's reuse switch / removal; the Preview closes only when the composer really opened
        TemplateActions.use(this, json, stickers, ownerUid, statusId, ownerName,
                () -> launched = true,
                () -> { if (launched) finish(); });
    }

    // ── animated preview ─────────────────────────────────────────────────────

    private void startLoop() {
        if (loop != null || preview == null) return;
        final int n = Math.max(1, preview.photoCount() - 1);   // photo boxes (base is not a box)
        loop = ValueAnimator.ofFloat(0f, n);
        loop.setDuration(LOOP_MS_PER_BOX * n);
        loop.setInterpolator(new LinearInterpolator());
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.addUpdateListener(a -> preview.setFocusPhase((float) a.getAnimatedValue()));
        loop.start();
        if (playBadge != null) playBadge.setVisibility(View.GONE);
    }

    private void stopLoop() {
        if (loop != null) { loop.cancel(); loop = null; }
    }

    private void togglePlay() {
        playing = !playing;
        if (playing) {
            startLoop();
        } else {
            stopLoop();
            preview.setFocusPhase(-1f);                      // static, all boxes at full strength
            if (playBadge != null) playBadge.setVisibility(View.VISIBLE);
        }
    }
}
