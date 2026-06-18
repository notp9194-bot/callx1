package com.callx.app.repost;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * BottomSheet shown when user taps the Repost button on a reel.
 * Features:
 *  • Simple repost (one tap)
 *  • Repost with caption
 *  • Quote repost (your video overlay)
 *  • Repost to Story
 *  • AI caption suggestion
 *  • Privacy toggle (allowRepostLevel)
 */
public class RepostBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "RepostBottomSheet";

    private static final String ARG_REEL_ID       = "reelId";
    private static final String ARG_OWNER_UID     = "ownerUid";
    private static final String ARG_OWNER_NAME    = "ownerName";
    private static final String ARG_REEL_THUMB    = "reelThumb";
    private static final String ARG_REEL_VIDEO    = "reelVideo";
    private static final String ARG_HAS_REPOSTED  = "hasReposted";

    private String reelId, ownerUid, ownerName, reelThumb, reelVideo;
    private boolean hasReposted;

    private RepostManager repostManager;
    private EditText etCaption;
    private TextView tvCharCount, tvAiHint;
    private MaterialButton btnRepost, btnUndo;
    private Chip chipQuote, chipStory;
    private ImageButton btnAiSuggest;
    private LinearLayout layoutCaption;
    private ProgressBar progressRepost;

    private RepostDoneListener doneListener;

    public interface RepostDoneListener {
        void onRepostDone(boolean isNowReposted, long newCount);
    }

    public static RepostBottomSheetFragment newInstance(String reelId, String ownerUid,
            String ownerName, String reelThumb, String reelVideo, boolean hasReposted) {
        RepostBottomSheetFragment f = new RepostBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_REEL_ID,      reelId);
        b.putString(ARG_OWNER_UID,    ownerUid);
        b.putString(ARG_OWNER_NAME,   ownerName);
        b.putString(ARG_REEL_THUMB,   reelThumb);
        b.putString(ARG_REEL_VIDEO,   reelVideo);
        b.putBoolean(ARG_HAS_REPOSTED, hasReposted);
        f.setArguments(b);
        return f;
    }

    public void setDoneListener(RepostDoneListener l) { doneListener = l; }

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (getArguments() != null) {
            reelId      = getArguments().getString(ARG_REEL_ID);
            ownerUid    = getArguments().getString(ARG_OWNER_UID);
            ownerName   = getArguments().getString(ARG_OWNER_NAME);
            reelThumb   = getArguments().getString(ARG_REEL_THUMB);
            reelVideo   = getArguments().getString(ARG_REEL_VIDEO);
            hasReposted = getArguments().getBoolean(ARG_HAS_REPOSTED, false);
        }
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) {
            repostManager = new RepostManager(
                u.getUid(),
                u.getDisplayName() != null ? u.getDisplayName() : "User",
                u.getPhotoUrl() != null ? u.getPhotoUrl().toString() : ""
            );
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c,
                             @Nullable Bundle s) {
        View v = inf.inflate(R.layout.bottom_sheet_repost, c, false);
        bindViews(v);
        setupUI();
        return v;
    }

    private void bindViews(View v) {
        etCaption    = v.findViewById(R.id.et_repost_caption);
        tvCharCount  = v.findViewById(R.id.tv_char_count);
        tvAiHint     = v.findViewById(R.id.tv_ai_hint);
        btnRepost    = v.findViewById(R.id.btn_repost);
        btnUndo      = v.findViewById(R.id.btn_undo_repost);
        chipQuote    = v.findViewById(R.id.chip_quote_repost);
        chipStory    = v.findViewById(R.id.chip_repost_story);
        btnAiSuggest = v.findViewById(R.id.btn_ai_suggest);
        layoutCaption= v.findViewById(R.id.layout_caption);
        progressRepost = v.findViewById(R.id.progress_repost);
    }

    private void setupUI() {
        if (hasReposted) {
            btnRepost.setVisibility(View.GONE);
            btnUndo.setVisibility(View.VISIBLE);
        } else {
            btnRepost.setVisibility(View.VISIBLE);
            btnUndo.setVisibility(View.GONE);
        }

        // Character counter
        etCaption.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                tvCharCount.setText(s.length() + "/150");
            }
            public void afterTextChanged(Editable s) {}
        });

        // AI caption suggest
        btnAiSuggest.setOnClickListener(v -> suggestAICaption());

        // Simple repost
        btnRepost.setOnClickListener(v -> doRepost("simple"));

        // Undo repost
        btnUndo.setOnClickListener(v -> undoRepost());

        // Quote repost
        chipQuote.setOnClickListener(v -> openQuoteRepost());

        // Repost to Story
        chipStory.setOnClickListener(v -> doRepostToStory());
    }

    private void doRepost(String type) {
        String caption = etCaption.getText().toString().trim();
        setLoading(true);
        repostManager.doRepost(reelId, ownerUid, caption, type, (isNow, err) -> {
            setLoading(false);
            if (err != null) {
                Toast.makeText(getContext(), "Error: " + err, Toast.LENGTH_SHORT).show();
                return;
            }
            RepostManager.checkAndSetViralBadge(reelId, 0); // checked server-side
            // Send notification
            RepostNotificationWorker.enqueue(requireContext(), reelId, ownerUid,
                    repostManager.myUid(), repostManager.myName(),
                    repostManager.myPhoto(), reelThumb, caption);
            if (doneListener != null) doneListener.onRepostDone(true, -1);
            dismissAllowingStateLoss();
        });
    }

    private void doRepost(String type, RepostManager.RepostCallback cb) {
        String caption = etCaption.getText().toString().trim();
        repostManager.doRepost(reelId, ownerUid, caption, type, (isNow, err) -> {
            if (cb != null) cb.onSuccess(isNow);
        });
    }

    private void undoRepost() {
        setLoading(true);
        repostManager.removeRepost(reelId, (isNow, err) -> {
            setLoading(false);
            if (doneListener != null) doneListener.onRepostDone(false, -1);
            dismissAllowingStateLoss();
        });
    }

    private void openQuoteRepost() {
        Intent i = new Intent(requireContext(), QuoteRepostActivity.class);
        i.putExtra(QuoteRepostActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(QuoteRepostActivity.EXTRA_OWNER_UID,  ownerUid);
        i.putExtra(QuoteRepostActivity.EXTRA_REEL_VIDEO, reelVideo);
        i.putExtra(QuoteRepostActivity.EXTRA_REEL_THUMB, reelThumb);
        startActivity(i);
        dismissAllowingStateLoss();
    }

    private void doRepostToStory() {
        setLoading(true);
        repostManager.repostToStory(reelId, reelVideo, reelThumb, ownerName,
            (isNow, err) -> {
                setLoading(false);
                if (err != null) {
                    Toast.makeText(getContext(), "Story error: " + err, Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(getContext(), "Added to your Story!", Toast.LENGTH_SHORT).show();
                dismissAllowingStateLoss();
            });
    }

    private void suggestAICaption() {
        tvAiHint.setVisibility(View.VISIBLE);
        CollabAIHelper.suggestRepostCaption(reelId, ownerName, caption -> {
            etCaption.setText(caption);
            tvAiHint.setVisibility(View.GONE);
        });
    }

    private void setLoading(boolean loading) {
        progressRepost.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRepost.setEnabled(!loading);
        btnUndo.setEnabled(!loading);
    }

    // Proxy for inner lambda
    private class RepostCallbackImpl implements RepostManager.RepostCallback {
        @Override public void onSuccess(boolean isNow) {}
        @Override public void onError(String msg) {}
    }
}
