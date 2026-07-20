package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.community.canvas.CommunityAvatarPreloader;
import com.callx.app.community.canvas.CommunityScrollOptimizer;
import com.callx.app.db.entity.CommunityEventEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v34: Events tab — now supports 3-way RSVP (Going / Interested / Not Going).
 * Cover images shown on each event card.
 */
public class CommunityEventsFragment extends Fragment implements CommunityEventAdapter.Listener {

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

    @Nullable @Override
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
        CommunityScrollOptimizer.apply(rvEvents, llm);
        rvEvents.setHasFixedSize(false);
        rvEvents.setItemAnimator(null);
        rvEvents.setOverScrollMode(View.OVER_SCROLL_NEVER);

        adapter = new CommunityEventAdapter(this);
        adapter.setCurrentUid(currentUid);
        rvEvents.setAdapter(adapter);
        // Glide cover-image preloader — pre-fetches event covers 6 items ahead
        rvEvents.post(() -> CommunityAvatarPreloader.attachCover(this, rvEvents,
                new CommunityAvatarPreloader.UrlProvider() {
                    @Override public String urlAt(int pos) {
                        java.util.List<com.callx.app.db.entity.CommunityEventEntity> list =
                                adapter.getCurrentList();
                        return (pos >= 0 && pos < list.size()) ? list.get(pos).coverImageUrl : null;
                    }
                    @Override public int count() { return adapter.getItemCount(); }
                }, rvEvents.getWidth() > 0 ? rvEvents.getWidth()
                : getResources().getDisplayMetrics().widthPixels, 160));

        fabCreate.setOnClickListener(v -> openCreateEvent());

        if (communityId != null) {
            repo.observeUpcomingEvents(communityId, System.currentTimeMillis())
                    .observe(getViewLifecycleOwner(), this::onEventsUpdated);
            repo.observeMembers(communityId)
                    .observe(getViewLifecycleOwner(), this::onMembersUpdated);
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
        if (!isAdded() || members == null || currentUid == null) return;
        for (CommunityMemberEntity m : members) {
            if (currentUid.equals(m.uid)) {
                myRole = m.role != null ? m.role : CommunityRole.MEMBER; break;
            }
        }
        boolean isAdmin = CommunityRole.isAdminOrOwner(myRole);
        if (fabCreate != null)
            fabCreate.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onEventClicked(CommunityEventEntity event) {
        // Show details — for now show a toast; full detail activity can be added later
        if (!isAdded()) return;
        Toast.makeText(requireContext(), event.title, Toast.LENGTH_SHORT).show();
    }

    /**
     * v34: Handle 3-way RSVP from event card buttons.
     * Updates Firebase rsvpJson + individual counters atomically.
     */
    @Override
    public void onRsvp(CommunityEventEntity event, String newStatus) {
        if (currentUid == null || !isAdded()) return;

        // Determine previous status from rsvpJson
        String prevStatus = null;
        try {
            if (event.rsvpJson != null) {
                JSONObject obj = new JSONObject(event.rsvpJson);
                prevStatus = obj.optString(currentUid, null);
                if (prevStatus != null && prevStatus.isEmpty()) prevStatus = null;
            }
        } catch (Exception ignored) {}

        // If tapping same status again → un-RSVP
        boolean toggling = newStatus.equals(prevStatus);
        final String finalStatus = toggling ? null : newStatus;
        final String removedStatus = prevStatus;

        com.google.firebase.database.DatabaseReference evRef =
                FirebaseDatabase.getInstance().getReference("communities")
                        .child(communityId).child("events").child(event.id);

        Map<String, Object> updates = new HashMap<>();
        // Update rsvp map
        updates.put("rsvp/" + currentUid, finalStatus); // null removes the node via updateChildren

        // Counter adjustments
        if (removedStatus != null) {
            long oldCount = countFor(event, removedStatus);
            if (oldCount > 0) updates.put(counterKey(removedStatus), oldCount - 1);
        }
        if (finalStatus != null) {
            long newCount = countFor(event, finalStatus);
            updates.put(counterKey(finalStatus), newCount + 1);
        }

        evRef.updateChildren(updates)
                .addOnFailureListener(e -> {
                    if (isAdded())
                        Toast.makeText(requireContext(), "RSVP failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                });

        // Optimistic local feedback
        if (isAdded()) {
            String msg;
            if (toggling)     msg = "RSVP removed";
            else if ("going".equals(finalStatus))       msg = "✅ Marked as Going!";
            else if ("interested".equals(finalStatus))  msg = "⭐ Marked as Interested!";
            else                                         msg = "❌ Marked as Not Going";
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    private long countFor(CommunityEventEntity ev, String status) {
        if ("going".equals(status))        return ev.rsvpCount;
        if ("interested".equals(status))   return ev.interestedCount;
        if ("not_going".equals(status))    return ev.notGoingCount;
        return 0L;
    }

    private String counterKey(String status) {
        if ("going".equals(status))      return "rsvpCount";
        if ("interested".equals(status)) return "interestedCount";
        return "notGoingCount";
    }

    private void openCreateEvent() {
        if (!isAdded()) return;
        Intent i = new Intent(requireContext(), CommunityCreateEventActivity.class);
        i.putExtra(CommunityCreateEventActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }
}
