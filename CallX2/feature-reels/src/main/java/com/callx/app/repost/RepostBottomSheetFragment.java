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
import com.callx.app.R;
import com.callx.app.utils.CollabAIHelper;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class RepostBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "RepostBottomSheet";

    private static final String ARG_REEL_ID      = "reelId";
    private static final String ARG_OWNER_UID    = "ownerUid";
    private static final String ARG_OWNER_NAME   = "ownerName";
    private static final String ARG_REEL_THUMB   = "reelThumb";
    private static final String ARG_REEL_VIDEO   = "reelVideo";
    private static final String ARG_HAS_REPOSTED = "hasReposted";

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
                u.getPhotoUrl()    != null ? u.getPhotoUrl().toString() : ""
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
        etCaption     = v.findViewById(R.id.et_repost_caption);
        tvCharCount   = v.findViewById(R.id.tv_char_count);
        tvAiHint      = v.findViewById(R.id.tv_ai_hint);
        btnRepost     = v.findViewById(R.id.btn_repost);
        btnUndo       = v.findViewById(R.id.btn_undo_repost);
        chipQuote     = v.findViewById(R.id.chip_quote_repost);
        chipStory     = v.findViewById(R.id.chip_repost_story);
        btnAiSuggest  = v.findViewById(R.id.btn_ai_suggest);
        layoutCaption = v.findViewById(R.id.layout_caption);
        progressRepost= v.findViewById(R.id.progress_repost);
    }

    private void setupUI() {
        if (hasReposted) {
            btnRepost.setVisibility(View.GONE);
            btnUndo.setVisibility(View.VISIBLE);
        } else {
            btnRepost.setVisibility(View.VISIBLE);
            btnUndo.setVisibility(View.GONE);
        }
        etCaption.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                tvCharCount.setText(s.length() + "/150");
            }
            public void afterTextChanged(Editable s) {}
        });
        btnAiSuggest.setOnClickListener(v -> suggestAICaption());
        btnRepost.setOnClickListener(v -> doRepost("simple"));
        btnUndo.setOnClickListener(v -> undoRepost());
        chipQuote.setOnClickListener(v -> openQuoteRepost());
        chipStory.setOnClickListener(v -> doRepostToStory());
    }

    private void doRepost(String type) {
        if (repostManager == null) return;
        String caption = etCaption.getText().toString().trim();
        setLoading(true);
        repostManager.doRepost(reelId, ownerUid, caption, type, (ok, err) -> {
            setLoading(false);
            if (err != null) {
                Toast.makeText(getContext(), "Error: " + err, Toast.LENGTH_SHORT).show();
                return;
            }
            RepostNotificationWorker.enqueue(requireContext(), reelId, ownerUid,
                    repostManager.myUid(), repostManager.myName(),
                    repostManager.myPhoto(), reelThumb, caption);
            if (doneListener != null) doneListener.onRepostDone(true, -1);
            dismissAllowingStateLoss();
        });
    }

    private void undoRepost() {
        if (repostManager == null) return;
        setLoading(true);
        repostManager.removeRepost(reelId, (ok, err) -> {
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
        if (repostManager == null) return;
        setLoading(true);
        repostManager.repostToStory(reelId, reelVideo, reelThumb, ownerName, (ok, err) -> {
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
        if (tvAiHint != null) tvAiHint.setVisibility(View.VISIBLE);
        CollabAIHelper.suggestRepostCaption(reelId, ownerName, caption -> {
            if (etCaption != null) etCaption.setText(caption);
            if (tvAiHint  != null) tvAiHint.setVisibility(View.GONE);
        });
    }

    private void setLoading(boolean loading) {
        if (progressRepost != null) progressRepost.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnRepost  != null) btnRepost.setEnabled(!loading);
        if (btnUndo    != null) btnUndo.setEnabled(!loading);
    }
}
