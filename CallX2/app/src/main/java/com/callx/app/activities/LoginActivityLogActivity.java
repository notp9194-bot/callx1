package com.callx.app.activities;

import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.utils.SecurityManager;
import java.util.List;

/**
 * Shows login/device activity log with current session highlighted.
 * Lets the user clear the history.
 */
public class LoginActivityLogActivity extends AppCompatActivity {

    private SecurityManager secMgr;
    private RecyclerView recycler;
    private TextView emptyTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secMgr = new SecurityManager(this);

        // Record this session
        secMgr.recordLoginEvent();

        // Build layout programmatically
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(getColor(R.color.surface_bg));

        Toolbar tb = new Toolbar(this);
        tb.setTitle("Login Activity");
        tb.setTitleTextColor(0xFFFFFFFF);
        tb.setBackgroundColor(getColor(R.color.brand_primary));
        tb.setNavigationIcon(R.drawable.ic_back);
        tb.getNavigationIcon().setTint(0xFFFFFFFF);
        tb.setNavigationOnClickListener(v -> finish());
        tb.inflateMenu(R.menu.main_menu); // we'll add clear option
        tb.setOnMenuItemClickListener(item -> {
            if ("Clear History".equals(item.getTitle().toString())) {
                confirmClear();
                return true;
            }
            return false;
        });
        // Add clear menu item
        tb.getMenu().add(0, 999, 0, "Clear History")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        // Info banner
        TextView info = new TextView(this);
        info.setText("This device is shown as 'Current session'. All entries below are login events recorded on this device.");
        info.setTextColor(getColor(R.color.text_secondary));
        info.setTextSize(13);
        info.setPadding(dp(16), dp(12), dp(16), dp(12));
        info.setBackgroundColor(0xFFEEF2FF);

        // Empty view
        emptyTv = new TextView(this);
        emptyTv.setText("No login history yet.");
        emptyTv.setGravity(Gravity.CENTER);
        emptyTv.setTextColor(getColor(R.color.text_muted));
        emptyTv.setTextSize(15);
        emptyTv.setVisibility(View.GONE);
        emptyTv.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        outer.addView(tb);
        outer.addView(info);
        outer.addView(emptyTv);
        outer.addView(recycler);
        setContentView(outer);

        loadHistory();
    }

    private void loadHistory() {
        List<SecurityManager.LoginEvent> history = secMgr.getLoginHistory();
        if (history.isEmpty()) {
            emptyTv.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
        } else {
            emptyTv.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
            recycler.setAdapter(new LogAdapter(history));
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("Clear Login History?")
            .setMessage("All login records will be permanently deleted.")
            .setPositiveButton("Clear", (d, w) -> {
                secMgr.clearLoginHistory();
                loadHistory();
                Toast.makeText(this, "Login history cleared", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {
        private final List<SecurityManager.LoginEvent> items;
        LogAdapter(List<SecurityManager.LoginEvent> items) { this.items = items; }

        @Override public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Left: icon
            ImageView icon = new ImageView(parent.getContext());
            icon.setImageResource(R.drawable.ic_phone);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
            icon.setColorFilter(getColor(R.color.brand_primary));

            // Right: text block
            LinearLayout texts = new LinearLayout(parent.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            textsLp.setMarginStart(dp(14));
            texts.setLayoutParams(textsLp);

            TextView deviceTv = new TextView(parent.getContext());
            deviceTv.setTextColor(getColor(R.color.text_primary));
            deviceTv.setTextSize(14);
            deviceTv.setId(R.id.tv_menu_title);

            TextView timeTv = new TextView(parent.getContext());
            timeTv.setTextColor(getColor(R.color.text_muted));
            timeTv.setTextSize(12);
            timeTv.setId(R.id.tv_menu_subtitle);

            // Badge: current session
            TextView badge = new TextView(parent.getContext());
            badge.setText("CURRENT");
            badge.setTextColor(0xFF22D3A6);
            badge.setTextSize(10);
            badge.setTypeface(null, android.graphics.Typeface.BOLD);
            badge.setVisibility(View.GONE);
            badge.setId(R.id.iv_menu_icon); // reuse id

            texts.addView(deviceTv);
            texts.addView(timeTv);
            texts.addView(badge);

            card.addView(icon);
            card.addView(texts);

            // Divider-like row separator
            LinearLayout wrapper = new LinearLayout(parent.getContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.addView(card);
            View divider = new View(parent.getContext());
            divider.setBackgroundColor(getColor(R.color.divider));
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
            wrapper.addView(divider);
            wrapper.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(wrapper, deviceTv, timeTv, badge);
        }

        @Override public void onBindViewHolder(VH vh, int position) {
            SecurityManager.LoginEvent ev = items.get(position);
            vh.deviceTv.setText(ev.device);
            vh.timeTv.setText(ev.time);
            vh.badge.setVisibility(ev.isCurrent ? View.VISIBLE : View.GONE);
            vh.itemView.setBackgroundColor(ev.isCurrent ? 0xFFEEF2FF : 0xFFFFFFFF);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView deviceTv, timeTv, badge;
            VH(View v, TextView d, TextView t, TextView b) {
                super(v); deviceTv = d; timeTv = t; badge = b;
            }
        }
    }
}
