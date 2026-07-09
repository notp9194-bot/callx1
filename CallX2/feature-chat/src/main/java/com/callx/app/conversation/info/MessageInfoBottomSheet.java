package com.callx.app.conversation.info;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.chat.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

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
 */
public class MessageInfoBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "MessageInfoBottomSheet";

    public static MessageInfoBottomSheet newInstance() {
        return new MessageInfoBottomSheet();
    }

    private MaxHeightRecyclerView rv;

    @Override
    public void onStart() {
        super.onStart();
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

        List<MessageInfoRow> rows = MessageInfoRowBuilder.build(data);

        rv = v.findViewById(R.id.rv_message_info);
        rv.setMaxHeightPx((int) (getResources().getDisplayMetrics().heightPixels * 0.6f));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setItemViewCacheSize(8);
        rv.setAdapter(new MessageInfoAdapter(rows));

        v.findViewById(R.id.iv_sheet_close).setOnClickListener(x -> dismiss());
    }

    @Override
    public void onDestroyView() {
        if (rv != null) rv.setAdapter(null);
        rv = null;
        super.onDestroyView();
    }
}
