package com.callx.app.conversation.info;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MessageInfoBottomSheet — replaces MessageInfoActivity.
 *
 * The old screen was a separate full-screen Activity launched with
 * startActivity() every time a user tapped "Info" on a single selected
 * message. That meant a whole extra Window + theme/toolbar inflate +
 * Activity lifecycle stacked on top of an already-heavy ChatActivity, and
 * the same teardown again on the way back (ChatActivity's own message
 * RecyclerView has to reattach/redraw on resume) — a visible hitch right
 * when the chat is mid-interaction. A BottomSheetDialogFragment reuses the
 * host Activity's window; ChatActivity/GroupChatActivity and their message
 * lists just sit underneath, untouched, while this shows/dismisses.
 *
 * The row list itself also moved off the old ScrollView + manual addView()
 * loop (which eagerly inflated every row — and fired a Glide load for every
 * group-member avatar — synchronously on open) onto a real RecyclerView
 * (MessageInfoAdapter), so opening this no longer does work proportional to
 * how many members are in the group.
 *
 * MessageInfoRowBuilder.build() is now also run off the main thread
 * (ROW_BUILD_EXECUTOR). It's cheap for a 1:1 chat (3 rows) but for a large
 * group's read/delivered/pending breakdown it's real list work — running it
 * on the sheet's opening frame was one more thing competing with the sheet's
 * own expand animation for main-thread time. A brief indeterminate spinner
 * covers the (usually sub-frame) gap; the RecyclerView only appears once
 * rows are ready to bind.
 */
public class MessageInfoBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "MessageInfoBottomSheet";

    /**
     * Implemented by ChatActivity/GroupChatActivity. While this sheet is open
     * it fully covers the host's own message RecyclerView — nothing the user
     * does can scroll it — so there's no reason for it to keep doing scroll
     * bookkeeping (preload-ahead, read-receipt-on-scroll checks, etc.) at the
     * same time this sheet's own RecyclerView is laying out/animating open.
     * Two RecyclerViews doing layout/scroll work in the same frame is exactly
     * the kind of double-compute that causes a hitch; pausing the hidden one
     * removes it from the frame budget entirely until the sheet closes.
     */
    public interface HostRecyclerPauseListener {
        void onMessageInfoOpened();
        void onMessageInfoClosed();
    }

    private HostRecyclerPauseListener hostListener;

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof HostRecyclerPauseListener) {
            hostListener = (HostRecyclerPauseListener) context;
        }
    }

    private static final ExecutorService ROW_BUILD_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * Shared across every MessageInfoBottomSheet instance (any chat, any
     * open) — NOT with ChatActivity/GroupChatActivity's own message-list
     * RecyclerView. That list renders bubbles as Canvas views
     * (MessageBubbleCanvasView) with completely different view-holder
     * shapes than this sheet's avatar+name+tick member rows, and its
     * viewType ints (1,2,3,4,5,11,12 — see ChatActivity's pool setup)
     * directly collide with this adapter's (VT_STATUS=1, VT_HEADER=2,
     * VT_MEMBER=3, VT_EMPTY=4). Sharing that pool would let it hand back a
     * canvas bubble ViewHolder for a member-row viewType — a ClassCastException
     * waiting to happen, not a perf win. What genuinely repeats here is
     * MEMBER rows themselves — a large group's "Read by" list opened once,
     * then reopened for another message (or another chat) — so the pool is
     * scoped to this adapter's own view types and reused across sheet opens.
     */
    private static final RecyclerView.RecycledViewPool SHEET_VIEW_POOL = new RecyclerView.RecycledViewPool();
    static {
        SHEET_VIEW_POOL.setMaxRecycledViews(MessageInfoAdapter.VT_MEMBER, 20);
    }

    public static MessageInfoBottomSheet newInstance() {
        return new MessageInfoBottomSheet();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MaxHeightRecyclerView rv;
    private ProgressBar progressBar;
    private MessageInfoAdapter adapter;

    @Override
    public void onStart() {
        super.onStart();
        if (hostListener != null) hostListener.onMessageInfoOpened();
        if (!(getDialog() instanceof BottomSheetDialog)) return;
        BottomSheetDialog d = (BottomSheetDialog) getDialog();
        FrameLayout bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs == null) return;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bs);
        behavior.setHideable(true);
        behavior.setSkipCollapsed(true);
        behavior.setFitToContents(true);
        behavior.setDraggable(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_message_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        // One-shot handoff, same as the old Activity — MessageInfoBridge.set()
        // is called right before this sheet is shown. Nothing there means
        // there's nothing to show; bail with no half-drawn sheet flash.
        MessageInfoData data = MessageInfoBridge.take();
        if (data == null) {
            dismissAllowingStateLoss();
            return;
        }

        rv = v.findViewById(R.id.rv_message_info);
        progressBar = v.findViewById(R.id.progress_message_info);
        rv.setMaxHeightPx((int) (getResources().getDisplayMetrics().heightPixels * 0.6f));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setItemViewCacheSize(8);
        rv.setItemAnimator(null);
        rv.setRecycledViewPool(SHEET_VIEW_POOL);
        adapter = new MessageInfoAdapter();
        rv.setAdapter(adapter);

        v.findViewById(R.id.iv_sheet_close).setOnClickListener(x -> dismiss());

        ROW_BUILD_EXECUTOR.execute(() -> {
            List<MessageInfoRow> rows = MessageInfoRowBuilder.build(data);
            mainHandler.post(() -> {
                // Fragment may have been dismissed while rows were building
                // on the background thread — view's gone, nothing to bind.
                if (!isAdded() || getView() == null || adapter == null) return;
                adapter.submitList(rows);
                progressBar.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
            });
        });
    }

    @Override
    public void onDetach() {
        hostListener = null;
        super.onDetach();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (hostListener != null) hostListener.onMessageInfoClosed();
    }

    @Override
    public void onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null);
        if (rv != null) rv.setAdapter(null);
        rv = null;
        progressBar = null;
        adapter = null;
        super.onDestroyView();
    }
}
