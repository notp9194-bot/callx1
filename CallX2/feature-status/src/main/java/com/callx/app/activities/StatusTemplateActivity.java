package com.callx.app.activities;

import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.utils.StatusTemplateManager;
import com.callx.app.utils.StatusTemplateManager.Template;
import java.util.*;

/**
 * StatusTemplateActivity v26 — Template picker grid.
 * Returns: EXTRA_TEMPLATE_ID, EXTRA_BG_COLOR, EXTRA_TEXT_COLOR, EXTRA_FONT_STYLE.
 */
public class StatusTemplateActivity extends AppCompatActivity {
    public static final String EXTRA_TEMPLATE_ID = "template_id";
    public static final String EXTRA_BG_COLOR    = "bg_color";
    public static final String EXTRA_BG_COLOR2   = "bg_color2";
    public static final String EXTRA_TEXT_COLOR  = "text_color";
    public static final String EXTRA_FONT_STYLE  = "font_style";
    public static final String EXTRA_TEXT_ALIGN  = "text_align";

    private TemplateAdapter adapter;
    private String selectedCategory = "All";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        // Title
        TextView title = new TextView(this); title.setText("✨ Choose a Template");
        title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(16),dp(16),dp(16),dp(8)); root.addView(title);

        // Category filter
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout catRow = new LinearLayout(this); catRow.setOrientation(LinearLayout.HORIZONTAL);
        catRow.setPadding(dp(12),0,dp(12),dp(8));
        for (String cat : StatusTemplateManager.getCategories()) {
            Button btn = new Button(this); btn.setText(cat); btn.setTextSize(12);
            btn.setPadding(dp(12),dp(4),dp(12),dp(4));
            btn.setOnClickListener(v -> {
                selectedCategory = cat;
                adapter.setData(StatusTemplateManager.getByCategory(cat));
                refreshCatButtons(catRow, cat);
            });
            btn.setTag(cat); catRow.addView(btn);
        }
        hsv.addView(catRow); root.addView(hsv);

        // Grid RecyclerView
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        rv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new TemplateAdapter(template -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_TEMPLATE_ID, template.id);
            result.putExtra(EXTRA_BG_COLOR, template.bgColor);
            result.putExtra(EXTRA_BG_COLOR2, template.bgColor2);
            result.putExtra(EXTRA_TEXT_COLOR, template.textColor);
            result.putExtra(EXTRA_FONT_STYLE, template.fontStyle);
            result.putExtra(EXTRA_TEXT_ALIGN, template.textAlign);
            setResult(RESULT_OK, result); finish();
        });
        adapter.setData(StatusTemplateManager.getAllTemplates());
        rv.setAdapter(adapter); root.addView(rv);

        setContentView(root);
        refreshCatButtons(catRow, "All");
    }

    private void refreshCatButtons(LinearLayout row, String selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            boolean sel = selected.equals(v.getTag());
            ((Button)v).setBackgroundColor(sel ? Color.parseColor("#6200EE") : Color.parseColor("#333333"));
            ((Button)v).setTextColor(Color.WHITE);
        }
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    static class TemplateAdapter extends RecyclerView.Adapter<TemplateAdapter.VH> {
        interface OnSelect { void onSelect(Template t); }
        private List<Template> data = new ArrayList<>();
        private final OnSelect cb;
        TemplateAdapter(OnSelect cb){ this.cb=cb; }
        void setData(List<Template> d){ data=d; notifyDataSetChanged(); }
        @Override public int getItemCount(){ return data.size(); }
        @Override public VH onCreateViewHolder(ViewGroup p, int t){
            FrameLayout fl = new FrameLayout(p.getContext());
            int sz = p.getWidth()/3;
            fl.setLayoutParams(new RecyclerView.LayoutParams(sz, sz));
            return new VH(fl);
        }
        @Override public void onBindViewHolder(VH h, int pos){
            Template t = data.get(pos);
            FrameLayout fl = (FrameLayout) h.itemView; fl.removeAllViews();
            // Background
            View bg = new View(fl.getContext());
            if (t.bgColor2 != null) {
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{Color.parseColor(t.bgColor), Color.parseColor(t.bgColor2)});
                bg.setBackground(gd);
            } else { bg.setBackgroundColor(Color.parseColor(t.bgColor)); }
            bg.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            fl.addView(bg);
            // Emoji + name
            LinearLayout ll = new LinearLayout(fl.getContext()); ll.setOrientation(LinearLayout.VERTICAL);
            ll.setGravity(android.view.Gravity.CENTER);
            ll.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            TextView emoji = new TextView(fl.getContext()); emoji.setText(t.previewEmoji); emoji.setTextSize(28);
            emoji.setGravity(android.view.Gravity.CENTER);
            TextView name = new TextView(fl.getContext()); name.setText(t.name); name.setTextSize(10);
            name.setTextColor(t.textColor != null ? Color.parseColor(t.textColor) : Color.WHITE);
            name.setGravity(android.view.Gravity.CENTER);
            ll.addView(emoji); ll.addView(name); fl.addView(ll);
            fl.setOnClickListener(v -> cb.onSelect(t));
        }
        static class VH extends RecyclerView.ViewHolder { VH(View v){ super(v); } }
    }
}
