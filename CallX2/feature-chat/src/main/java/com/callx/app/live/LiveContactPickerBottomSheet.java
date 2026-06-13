package com.callx.app.live;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;

public class LiveContactPickerBottomSheet extends BottomSheetDialogFragment {

    private LiveContactPickerAdapter adapter;
    private final List<User> contacts = new ArrayList<>();
    private Button btnStartLive;
    private TextView tvSelectedCount;

    public static LiveContactPickerBottomSheet newInstance() {
        return new LiveContactPickerBottomSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_live_contact_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        RecyclerView rv    = v.findViewById(R.id.rv_live_contacts);
        EditText etSearch  = v.findViewById(R.id.et_live_search);
        btnStartLive       = v.findViewById(R.id.btn_start_live);
        tvSelectedCount    = v.findViewById(R.id.tv_live_selected_count);
        View btnClose      = v.findViewById(R.id.btn_live_picker_close);

        adapter = new LiveContactPickerAdapter(contacts);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);

        adapter.setOnSelectionChangedListener(count -> {
            tvSelectedCount.setText(count == 0 ? "Contacts select karo" :
                count + " selected");
            btnStartLive.setEnabled(count > 0);
            btnStartLive.setAlpha(count > 0 ? 1f : 0.5f);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnStartLive.setEnabled(false);
        btnStartLive.setAlpha(0.5f);
        btnStartLive.setOnClickListener(x -> startLive());

        if (btnClose != null) btnClose.setOnClickListener(x -> dismiss());

        loadContacts();
    }

    private void loadContacts() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                contacts.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    User u = child.getValue(User.class);
                    if (u != null && u.uid == null) u.uid = child.getKey();
                    if (u != null) contacts.add(u);
                }
                if (adapter != null) adapter.filter("");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void startLive() {
        List<User> selected = adapter.getSelectedContacts();
        if (selected.isEmpty()) {
            Toast.makeText(getContext(), "Pehle contacts select karo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        String liveId = LiveManager.generateLiveId();
        if (liveId == null) {
            Toast.makeText(getContext(), "Live shuru nahi ho saka, dobara try karo",
                Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> selectedUids = new ArrayList<>();
        ArrayList<String> selectedNames = new ArrayList<>();
        for (User u : selected) {
            if (u.uid != null) {
                selectedUids.add(u.uid);
                selectedNames.add(u.name != null ? u.name : "");
            }
        }

        dismiss();

        Intent intent = new Intent(getContext(), LiveActivity.class);
        intent.putExtra("liveId",        liveId);
        intent.putExtra("myUid",         myUid);
        intent.putStringArrayListExtra("invitedUids",  selectedUids);
        intent.putStringArrayListExtra("invitedNames", selectedNames);
        startActivity(intent);
    }
}
