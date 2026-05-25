package com.callx.app.adapters;
import android.view.*;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class RequestAdapter extends RecyclerView.Adapter<RequestAdapter.VH> {
    private final List<User> requests;
    public RequestAdapter(List<User> requests) { this.requests = requests; }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_request, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = requests.get(pos);
        h.tvName.setText(u.name == null ? "User" : u.name);
        h.btnAccept.setOnClickListener(v -> {
            String myUid = FirebaseUtils.getCurrentUid();
            String myName = FirebaseUtils.getCurrentName();
            Map<String, Object> them = new HashMap<>();
            them.put("uid", u.uid); them.put("name", u.name); them.put("emoji", "😊");
            FirebaseUtils.getContactsRef(myUid).child(u.uid).setValue(them);
            Map<String, Object> me = new HashMap<>();
            me.put("uid", myUid); me.put("name", myName); me.put("emoji", "😊");
            FirebaseUtils.getContactsRef(u.uid).child(myUid).setValue(me);
            FirebaseUtils.getRequestsRef(myUid).child(u.uid).removeValue();
            requests.remove(pos);
            notifyItemRemoved(pos);
        });
        h.btnReject.setOnClickListener(v -> {
            String myUid = FirebaseUtils.getCurrentUid();
            FirebaseUtils.getRequestsRef(myUid).child(u.uid).removeValue();
            requests.remove(pos);
            notifyItemRemoved(pos);
        });
    }
    @Override public int getItemCount() { return requests.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        Button btnAccept, btnReject;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            btnAccept = v.findViewById(R.id.btn_accept);
            btnReject = v.findViewById(R.id.btn_reject);
        }
    }
}
