package com.callx.app.bots;

import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.models.BotCommand;
import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal/vertical suggestion strip shown above the input bar
 * when the user types "/" in GroupChatActivity.
 * Tapping a row auto-fills the command in the EditText.
 */
public class BotCommandSuggestionsAdapter
        extends RecyclerView.Adapter<BotCommandSuggestionsAdapter.VH> {

    public interface OnCommandSelected { void onSelected(BotCommand cmd); }

    private final List<BotCommand> items = new ArrayList<>();
    private final OnCommandSelected listener;

    public BotCommandSuggestionsAdapter(OnCommandSelected l) { this.listener = l; }

    public void update(List<BotCommand> newList) {
        items.clear(); items.addAll(newList); notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_bot_suggestion, p, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
    @Override public int getItemCount() { return items.size(); }

    class VH extends RecyclerView.ViewHolder {
        final TextView tvCmd, tvDesc;
        VH(View v) { super(v); tvCmd = v.findViewById(R.id.tv_bot_cmd); tvDesc = v.findViewById(R.id.tv_bot_desc); }
        void bind(BotCommand bc) {
            tvCmd.setText("/" + bc.command);
            tvDesc.setText(bc.description);
            itemView.setOnClickListener(v -> { if (listener != null) listener.onSelected(bc); });
        }
    }
}
