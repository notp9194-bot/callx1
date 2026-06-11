package com.callx.app.social;

  import android.app.Activity;
  import android.content.Intent;
  import android.os.Bundle;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;

  import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
  import com.callx.app.reels.R;
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.*;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * DuetSeriesPickerBottomSheet — Shown during reel posting so creator can:
   *   (a) Pick an existing series to add this episode to, OR
   *   (b) Create a new series (launches DuetSeriesCreateActivity)
   *
   * Result delivered via SeriesPickListener callback.
   */
  public class DuetSeriesPickerBottomSheet extends BottomSheetDialogFragment {

      public interface SeriesPickListener {
          /** Called when user picks or creates a series. */
          void onSeriesPicked(String seriesId, String seriesTitle, int nextEpisodeNumber);
          /** Called when user taps "No Series" (clear series). */
          void onSeriesCleared();
      }

      private static final int REQ_CREATE_SERIES = 801;

      private SeriesPickListener listener;
      private RecyclerView rvSeries;
      private ProgressBar progressSeries;
      private View layoutNoSeries;
      private SeriesListAdapter adapter;
      private final List<SeriesEntry> seriesList = new ArrayList<>();

      public void setSeriesPickListener(SeriesPickListener l) { this.listener = l; }

      @Nullable
      @Override
      public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                               @Nullable Bundle savedInstanceState) {
          return inflater.inflate(R.layout.bottom_sheet_series_picker, container, false);
      }

      @Override
      public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
          super.onViewCreated(view, savedInstanceState);
          rvSeries        = view.findViewById(R.id.rv_my_series);
          progressSeries  = view.findViewById(R.id.progress_series_load);
          layoutNoSeries  = view.findViewById(R.id.layout_no_series);
          TextView btnCreate  = view.findViewById(R.id.btn_create_new_series);
          TextView btnClear   = view.findViewById(R.id.btn_clear_series);

          adapter = new SeriesListAdapter();
          rvSeries.setLayoutManager(new LinearLayoutManager(requireContext()));
          rvSeries.setAdapter(adapter);

          btnCreate.setOnClickListener(v -> {
              Intent i = new Intent(requireContext(), DuetSeriesCreateActivity.class);
              startActivityForResult(i, REQ_CREATE_SERIES);
          });
          btnClear.setOnClickListener(v -> {
              if (listener != null) listener.onSeriesCleared();
              dismiss();
          });

          loadMySeries();
      }

      private void loadMySeries() {
          String myUid = FirebaseUtils.getCurrentUid();
          if (myUid.isEmpty()) { layoutNoSeries.setVisibility(View.VISIBLE); return; }

          progressSeries.setVisibility(View.VISIBLE);
          FirebaseDatabase.getInstance(Constants.DB_URL)
              .getReference("userDuetSeries").child(myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()) return;
                      progressSeries.setVisibility(View.GONE);
                      seriesList.clear();
                      for (DataSnapshot s : snap.getChildren()) {
                          String id    = s.getKey();
                          String title = s.getValue(String.class);
                          if (id != null && title != null) seriesList.add(new SeriesEntry(id, title));
                      }
                      adapter.notifyDataSetChanged();
                      layoutNoSeries.setVisibility(seriesList.isEmpty() ? View.VISIBLE : View.GONE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (!isAdded()) return;
                      progressSeries.setVisibility(View.GONE);
                  }
              });
      }

      @Override
      public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
          super.onActivityResult(requestCode, resultCode, data);
          if (requestCode == REQ_CREATE_SERIES && resultCode == Activity.RESULT_OK && data != null) {
              String id    = data.getStringExtra(DuetSeriesCreateActivity.EXTRA_SERIES_ID);
              String title = data.getStringExtra(DuetSeriesCreateActivity.EXTRA_SERIES_TITLE);
              if (id != null && title != null) {
                  if (listener != null) listener.onSeriesPicked(id, title, 1);
                  dismiss();
              }
          }
      }

      // ── Inner: series entry ───────────────────────────────────────────────
      private static class SeriesEntry {
          String seriesId, title;
          SeriesEntry(String id, String t) { seriesId = id; title = t; }
      }

      // ── Inner: adapter ────────────────────────────────────────────────────
      private class SeriesListAdapter extends RecyclerView.Adapter<SeriesListAdapter.VH> {
          @NonNull
          @Override
          public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
              View v = LayoutInflater.from(p.getContext())
                  .inflate(android.R.layout.simple_list_item_1, p, false);
              return new VH(v);
          }
          @Override
          public void onBindViewHolder(@NonNull VH h, int pos) {
              SeriesEntry entry = seriesList.get(pos);
              h.tv.setText(entry.title);
              h.tv.setTextColor(0xFF000000);
              h.itemView.setOnClickListener(v -> {
                  // Fetch current episode count for this series to determine next episode number
                  FirebaseDatabase.getInstance(Constants.DB_URL)
                      .getReference("duetSeries").child(entry.seriesId).child("episodeCount")
                      .addListenerForSingleValueEvent(new ValueEventListener() {
                          @Override public void onDataChange(@NonNull DataSnapshot snap) {
                              int count = snap.getValue(Integer.class) != null
                                          ? snap.getValue(Integer.class) : 0;
                              if (listener != null)
                                  listener.onSeriesPicked(entry.seriesId, entry.title, count + 1);
                              dismiss();
                          }
                          @Override public void onCancelled(@NonNull DatabaseError e) {
                              if (listener != null)
                                  listener.onSeriesPicked(entry.seriesId, entry.title, 1);
                              dismiss();
                          }
                      });
              });
          }
          @Override public int getItemCount() { return seriesList.size(); }

          class VH extends RecyclerView.ViewHolder {
              TextView tv;
              VH(View v) { super(v); tv = v.findViewById(android.R.id.text1); }
          }
      }
  }
  