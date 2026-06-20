package com.callx.app.conversation.controllers;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import com.callx.app.chat.R;
import com.callx.app.chat.ui.MessageHighlightAnimator;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;

import androidx.recyclerview.widget.RecyclerView;

/**
 * ChatNavigationController — extracted from ChatActivity v21.
 *
 * Handles:
 *   openAvatarZoom / UserProfileActivity
 *   openEditProfile
 *   openAllMediaLinksDocs
 *   openSmallWindow
 *   navigateToOriginalMsg (reply-jump)
 */
public class ChatNavigationController {

    private final ChatActivityDelegate d;

    public ChatNavigationController(ChatActivityDelegate delegate) {
        this.d = delegate;
    }

    // ── Profile ───────────────────────────────────────────────────────────

    public void openAvatarZoom() {
        if (d.getPartnerUid() == null || d.getPartnerUid().isEmpty()) return;
        Intent intent = new Intent().setClassName(
            d.getActivity(), "com.callx.app.activities.UserProfileActivity");
        intent.putExtra("uid",    d.getPartnerUid());
        intent.putExtra("name",   d.getPartnerName()  != null ? d.getPartnerName()  : "");
        intent.putExtra("photo",  d.getPartnerPhoto() != null ? d.getPartnerPhoto() : "");
        intent.putExtra("chatId", d.getChatId()       != null ? d.getChatId()       : "");
        d.getActivity().startActivity(intent);
    }

    public void openEditProfile() {
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.ProfileActivity");
            d.getActivity().startActivity(new Intent(d.getActivity(), cls));
        } catch (ClassNotFoundException e) {
            d.showToast("Edit Profile unavailable");
        }
    }

    // ── Media / Links / Docs ──────────────────────────────────────────────

    public void openAllMediaLinksDocs() {
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.AllMediaLinksDocsActivity");
            Intent i = new Intent(d.getActivity(), cls);
            i.putExtra("chatId",      d.getChatId());
            i.putExtra("partnerName", d.getPartnerName());
            i.putExtra("isGroup",     false);
            d.getActivity().startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatNav", "AllMediaLinksDocsActivity not found", e);
        }
    }

    // ── Small Window ──────────────────────────────────────────────────────

    public void openSmallWindow() {
        android.content.Context appCtx = d.getActivity().getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(appCtx)) {
            Intent permIntent = new Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + appCtx.getPackageName()));
            d.getActivity().startActivity(permIntent);
            d.showToast("'Display over other apps' permission dijiye phir Small Window use karo");
            return;
        }
        try {
            Class<?> svcClass = Class.forName("com.callx.app.smallwindow.SmallWindowService");
            Intent svc = new Intent(appCtx, svcClass);
            svc.putExtra("name",   d.getPartnerName() != null ? d.getPartnerName() : "Chat");
            svc.putExtra("status", "CallX Small Window");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                appCtx.startForegroundService(svc);
            else
                appCtx.startService(svc);
            d.getActivity().moveTaskToBack(true);
        } catch (ClassNotFoundException e) {
            d.showToast("Small Window unavailable");
        }
    }

    // ── Navigate to original (reply jump) ─────────────────────────────────

    public void navigateToOriginalMsg(String messageId,
                                      RecyclerView rv,
                                      View fabBack) {
        if (messageId == null || messageId.isEmpty()) return;

        // 1. Try in-memory (adapter)
        int pos = -1;
        for (int i = 0; i < d.getPagingAdapter().getItemCount(); i++) {
            Message m = d.getPagingAdapter().peek(i);
            if (m != null && (messageId.equals(m.id) || messageId.equals(m.messageId))) {
                pos = i; break;
            }
        }

        if (pos >= 0) {
            MessageHighlightAnimator.scrollAndHighlight(rv, pos, fabBack);
            return;
        }

        // 2. Fallback: query Room for approx position
        final String cId = d.getChatId();
        d.getIoExecutor().execute(() -> {
            if (d.getDb() == null || cId == null) {
                d.runOnMain(() -> d.showToast("Message not in view — scroll up to find it"));
                return;
            }
            MessageEntity target = d.getDb().messageDao().getMessageById(messageId);
            if (target == null || target.timestamp == null) {
                d.runOnMain(() -> d.showToast("Original message not found"));
                return;
            }
            int posFromBottom = d.getDb().messageDao()
                .countMessagesAfterTimestamp(cId, target.timestamp);
            int approxPos = d.getPagingAdapter().getItemCount() - posFromBottom - 1;
            final int safePos = Math.max(0, approxPos);
            d.runOnMain(() -> {
                if (fabBack != null) {
                    fabBack.setVisibility(View.VISIBLE);
                    fabBack.animate().alpha(1f).setDuration(200).start();
                }
                rv.scrollToPosition(safePos);
                rv.postDelayed(() -> {
                    RecyclerView.ViewHolder vh =
                        rv.findViewHolderForAdapterPosition(safePos);
                    if (vh != null) MessageHighlightAnimator.flashHighlight(vh.itemView);
                }, 500);
            });
        });
    }
}
