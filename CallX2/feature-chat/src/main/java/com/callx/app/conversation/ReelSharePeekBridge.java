package com.callx.app.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;

import com.callx.app.models.Message;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chat-side adapter for the existing Reels peek player.
 *
 * feature-chat cannot directly depend on feature-reels because feature-status
 * already depends on feature-chat. The shared reel preview therefore remains
 * the single implementation in feature-reels and is reached here through a
 * tiny runtime bridge. This keeps chat and the Reels grid on the exact same
 * popup/player/ABR implementation instead of creating a second mini-player.
 */
final class ReelSharePeekBridge {

    private static final String CONTROLLER_CLASS =
            "com.callx.app.profile.ReelPeekPreviewController";

    // ── Chat-only size/position tweak ────────────────────────────────────
    // The shared popup_reel_peek.xml default card (331x475dp, used as-is by
    // UserReelsActivity's grid and SoundDetailFragment) is NOT used as the
    // base here anymore. For the chat screen's long-press peek (triggered
    // by holding a reel-share bubble — see MessageBubbleCanvasView's
    // onLongPress/onReelPeekPreview) the mini player is sized to exactly match the
    // reel-share card itself — same width/height, same 9:16 aspect — by
    // reusing MessageBubbleCanvasView's own card-size constants directly,
    // instead of independently scaling the shared popup default. This
    // guarantees the two stay pixel-identical even if the card size ever
    // changes again. The popup is, instead of the shared centered position,
    // anchored directly above the reel-share bubble via the controller's
    // anchorAboveSource flag. Both are passed through the 7-arg show()
    // overload below; every other screen keeps calling (or falling back to)
    // the plain 4-arg show(), so this only ever affects the chat screen.
    private static final float CARD_WIDTH_DP  =
            com.callx.app.conversation.canvas.MessageBubbleCanvasView.REEL_CARD_WIDTH_DP;
    private static final float CARD_HEIGHT_DP =
            com.callx.app.conversation.canvas.MessageBubbleCanvasView.REEL_CARD_HEIGHT_DP;

    private static final Map<Activity, Object> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ReelSharePeekBridge() {}

    static void show(@Nullable Context context, @Nullable Message message,
                     @Nullable View sourceView) {
        if (message == null || sourceView == null) return;

        Activity activity = findActivity(context != null ? context : sourceView.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        String reelId = trim(message.reelId);
        if (reelId.isEmpty()) return;

        FirebaseUtils.getReelsRef().child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        ReelModel reel = snapshot.getValue(ReelModel.class);
                        if (reel == null) reel = new ReelModel();

                        // The chat payload is intentionally used as a fallback
                        // for fields that may be absent on older reel records.
                        if (trim(reel.reelId).isEmpty()) reel.reelId = reelId;
                        if (trim(reel.caption).isEmpty()) reel.caption = message.reelShareCaption;
                        if (trim(reel.ownerName).isEmpty()) reel.ownerName = message.reelShareUsername;
                        if (trim(reel.ownerPhoto).isEmpty()) reel.ownerPhoto = message.reelShareOwnerPhoto;
                        if (trim(reel.effectiveThumbUrl()).isEmpty()) reel.thumbUrl = message.reelShareThumb;

                        final ReelModel resolvedReel = reel;
                        activity.runOnUiThread(() -> invokeController(
                                activity, resolvedReel, message, sourceView));
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        // The full-card tap remains available; do not show a
                        // broken/empty popup when the reel record is unavailable.
                    }
                });
    }

    private static void invokeController(Activity activity, ReelModel reel,
                                         Message message, View sourceView) {
        try {
            Class<?> controllerType = Class.forName(CONTROLLER_CLASS);
            Object controller = CONTROLLERS.get(activity);
            if (controller == null) {
                Constructor<?> constructor = controllerType.getConstructor(Activity.class);
                controller = constructor.newInstance(activity);
                CONTROLLERS.put(activity, controller);
            }

            Class<?> callbackType = Class.forName(CONTROLLER_CLASS + "$Callback");
            Object callback = Proxy.newProxyInstance(
                    callbackType.getClassLoader(),
                    new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if ("onWatchFull".equals(method.getName())) {
                            openFullReel(activity, message.reelId, message.reelShareUrl);
                        }
                        return null;
                    });

            // Card sized to exactly match the reel-share bubble (see
            // CARD_WIDTH_DP/CARD_HEIGHT_DP doc above), anchored above it
            // instead of screen-center. Falls back to the plain
            // centered/default-size 4-arg show() if the 7-arg overload isn't
            // present (e.g. an older feature-reels build on the classpath),
            // so the peek still works either way.
            try {
                float density = sourceView.getContext().getResources().getDisplayMetrics().density;
                int cardWidthPx  = Math.round(CARD_WIDTH_DP  * density);
                int videoHeightPx = Math.round(CARD_HEIGHT_DP * density);

                Method show7 = controllerType.getMethod(
                        "show", ReelModel.class, List.class, callbackType, View.class,
                        Integer.class, Integer.class, boolean.class);
                show7.invoke(controller, reel, null, callback, sourceView,
                        cardWidthPx, videoHeightPx, true);
            } catch (NoSuchMethodException noOverload) {
                Method show = controllerType.getMethod(
                        "show", ReelModel.class, List.class, callbackType, View.class);
                show.invoke(controller, reel, null, callback, sourceView);
            }
        } catch (Throwable ignored) {
            // The normal card tap is still functional if an older APK has no
            // peek controller on its classpath.
        }
    }

    private static void openFullReel(Activity activity, String reelId, String fallbackUrl) {
        String id = trim(reelId);
        String target = !id.isEmpty()
                ? Constants.DEEP_LINK_BASE_URL + "/reel/" + id
                : trim(fallbackUrl);
        if (target.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.setPackage(activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}