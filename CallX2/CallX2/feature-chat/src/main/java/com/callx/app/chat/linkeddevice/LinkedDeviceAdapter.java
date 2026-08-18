package com.callx.app.chat.linkeddevice;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.linkeddevice.LinkedDevice;

import java.util.ArrayList;
import java.util.List;

public class LinkedDeviceAdapter extends RecyclerView.Adapter<LinkedDeviceAdapter.VH> {

    public interface OnDeviceMenuClick {
        void onMenuClick(LinkedDevice device, View anchor);
    }

    private final List<LinkedDevice> devices = new ArrayList<>();
    private final OnDeviceMenuClick menuClick;

    public LinkedDeviceAdapter(OnDeviceMenuClick menuClick) {
        this.menuClick = menuClick;
    }

    public void submitList(List<LinkedDevice> newList) {
        devices.clear();
        devices.addAll(newList);
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return devices.isEmpty();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_linked_device, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LinkedDevice d = devices.get(position);
        h.icon.setText(iconFor(d.browser));
        h.name.setText(d.deviceName != null ? d.deviceName : (d.browser + " on " + d.os));

        long idleMs = System.currentTimeMillis() - d.lastActiveAt;
        String status = idleMs < 2 * 60 * 1000
                ? "Active now"
                : "Last active " + DateUtils.getRelativeTimeSpanString(d.lastActiveAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        h.status.setText(status + " · linked " + DateUtils.getRelativeTimeSpanString(d.linkedAt));

        h.menuBtn.setOnClickListener(v -> menuClick.onMenuClick(d, v));
        h.itemView.setOnClickListener(v -> menuClick.onMenuClick(d, h.menuBtn));
    }

    private String iconFor(String browser) {
        if (browser == null) return "\uD83D\uDCBB";
        String b = browser.toLowerCase();
        if (b.contains("chrome")) return "\uD83D\uDD35";
        if (b.contains("firefox")) return "\uD83E\uDD8A";
        if (b.contains("safari")) return "\uD83E\uDDED";
        if (b.contains("edge")) return "\uD83D\uDFE6";
        return "\uD83D\uDCBB";
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView icon, name, status, menuBtn;
        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.tv_device_icon);
            name = v.findViewById(R.id.tv_device_name);
            status = v.findViewById(R.id.tv_device_status);
            menuBtn = v.findViewById(R.id.btn_device_menu);
        }
    }
}
