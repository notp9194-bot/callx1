package com.callx.app.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.youtube.R;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeConnectedAppsActivity — Manage connected / authorized apps.
 * Shows apps that have access to YouTube account via OAuth.
 */
public class YouTubeConnectedAppsActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_connected_apps);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_connected_apps);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Connected Apps");
        }

        RecyclerView rv = findViewById(R.id.rv_yt_connected_apps);
        TextView tvEmpty = findViewById(R.id.tv_yt_connected_apps_empty);

        // Demo: show one connected app (this app itself)
        List<ConnectedApp> apps = new ArrayList<>();
        apps.add(new ConnectedApp("CallX2", "Social messaging + YouTube", "Connected on Jan 2024",
            R.drawable.ic_youtube_logo));

        if (apps.isEmpty()) {
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            if (rv != null) rv.setVisibility(View.GONE);
        } else {
            if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new ConnectedAppsAdapter(apps));
            }
        }
    }

    static class ConnectedApp {
        String name, description, connectedDate;
        int iconResId;
        ConnectedApp(String name, String description, String date, int icon) {
            this.name = name; this.description = description;
            this.connectedDate = date; this.iconResId = icon;
        }
    }

    class ConnectedAppsAdapter extends RecyclerView.Adapter<ConnectedAppsAdapter.VH> {
        List<ConnectedApp> apps;
        ConnectedAppsAdapter(List<ConnectedApp> apps) { this.apps = apps; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_yt_connected_app, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ConnectedApp app = apps.get(pos);
            h.tvName.setText(app.name);
            h.tvDesc.setText(app.description);
            h.tvDate.setText(app.connectedDate);
            if (h.ivIcon != null) h.ivIcon.setImageResource(app.iconResId);

            h.btnRevoke.setOnClickListener(v -> {
                new AlertDialog.Builder(v.getContext())
                    .setTitle("Revoke Access?")
                    .setMessage("\"" + app.name + "\" ka YouTube access revoke karna chahte ho?")
                    .setPositiveButton("Revoke", (dlg, w) -> {
                        apps.remove(pos);
                        notifyItemRemoved(pos);
                        Toast.makeText(v.getContext(), "Access revoked", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        @Override public int getItemCount() { return apps.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName, tvDesc, tvDate, btnRevoke;
            VH(@NonNull View v) {
                super(v);
                ivIcon   = v.findViewById(R.id.iv_yt_app_icon);
                tvName   = v.findViewById(R.id.tv_yt_app_name);
                tvDesc   = v.findViewById(R.id.tv_yt_app_desc);
                tvDate   = v.findViewById(R.id.tv_yt_app_date);
                btnRevoke = v.findViewById(R.id.btn_yt_app_revoke);
            }
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
