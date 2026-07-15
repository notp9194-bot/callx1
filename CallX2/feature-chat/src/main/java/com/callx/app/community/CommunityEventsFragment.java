package com.callx.app.community;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEventEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v31: Events tab fragment. Displays upcoming community events.
 * Admins/owners see a FAB to create new events.
 */
public class CommunityEventsFragment extends Fragment
        implements CommunityEventAdapter.Listener {

    private static final String ARG_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;
    private String myRole = CommunityRole.MEMBER;

    private RecyclerView rvEvents;
    private View layoutEmpty;
    private FloatingActionButton fabCreate;
    private CommunityEventAdapter adapter;
    private CommunityRepository repo;

    public static CommunityEventsFragment newInstance(String communityId) {
        CommunityEventsFragment f = new CommunityEventsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_COMMUNITY_ID, communityId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_community_events, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvEvents    = view.findViewById(R.id.rv_events);
        layoutEmpty = view.findViewById(R.id.layout_empty_events);
        fabCreate   = view.findViewById(R.id.fab_create_event);

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvEvents.setLayoutManager(llm);
        rvEvents.setHasFixedSize(false);
        rvEvents.setItemAnimator(null);
        rvEvents.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityEventAdapter(this);
        rvEvents.setAdapter(adapter);

        fabCreate.setOnClickListener(v -> openCreateEvent());

        if (communityId != null) {
            repo.observeUpcomingEvents(communityId, System.currentTimeMillis()).observe(
                    getViewLifecycleOwner(), this::onEventsUpdated);
            repo.observeMembers(communityId).observe(getViewLifecycleOwner(), this::onMembersUpdated);
        }
    }

    private void onEventsUpdated(List<CommunityEventEntity> events) {
        if (!isAdded()) return;
        adapter.submitList(events);
        boolean empty = events == null || events.isEmpty();
        rvEvents.setVisibility(empty ? View.GONE : View.VISIBLE);
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void onMembersUpdated(List<CommunityMemberEntity> members) {
        if (!isAdded() || members == null) return;
        for (CommunityMemberEntity m : members) {
            if (currentUid != null && currentUid.equals(m.uid)) {
                myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                break;
            }
        }
        fabCreate.setVisibility(CommunityRole.isAdminOrOwner(myRole) ? View.VISIBLE : View.GONE);
    }

    private void openCreateEvent() {
        Intent i = new Intent(requireContext(), CommunityCreateEventActivity.class);
        i.putExtra(CommunityCreateEventActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    @Override
    public void onEventClicked(CommunityEventEntity event) {
        if (!isAdded()) return;
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault());
        String dateStr = event.startTimeMs > 0 ? sdf.format(new Date(event.startTimeMs)) : "";
        StringBuilder msg = new StringBuilder();
        if (event.description != null && !event.description.isEmpty())
            msg.append(event.description).append("\n\n");
        if (dateStr.length() > 0) msg.append("📅 ").append(dateStr).append("\n");
        if (event.location != null && !event.location.isEmpty())
            msg.append("📍 ").append(event.location).append("\n");
        msg.append("👥 ").append(event.rsvpCount).append(" going");

        new AlertDialog.Builder(requireContext())
                .setTitle(event.title != null ? event.title : "Event")
                .setMessage(msg.toString())
                .setPositiveButton("RSVP Going", (d, w) -> {
                    if (currentUid != null) {
                        repo.rsvpEvent(communityId, event.id, currentUid, "going", null);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
