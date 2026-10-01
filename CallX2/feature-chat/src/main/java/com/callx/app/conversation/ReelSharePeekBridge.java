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
public final class ReelSharePeekBridge {

    private static final String CONTROLLER_CLASS =
            "com.callx.app.profile.ReelPeekPreviewController";

    // ── Chat-only size/position tweak ────────────────────────────────────
    // UX FIX: this used to reuse MessageBubbleCanvasView.REEL_CARD_WIDTH_DP
    // (165dp) directly, so the "preview" was pixel-identical to the static
    // inline bubble — too small to actually watch, it just looked like the
    // same bubble again. Now sized as a fraction of the CURRENT screen
    // (not a fixed dp), same 9:16 aspect, capped so it never dominates a
    // small device: width = 50% of screen width, height capped at 60% of
    // screen height (if 50%-width-at-9:16 would exceed that, shrink to fit
    // the height cap instead, keeping the aspect ratio). See
    // computeCardSizePx() below. The popup is, instead of the shared
    // centered position, anchored directly above the reel-share bubble via
    // the controller's anchorAboveSource flag. Both are passed through the
    // 7-arg show() overload below; every other screen keeps calling (or
    // falling back to) the plain 4-arg show(), so this only ever affects
    // the chat screen.
    private static final float WIDTH_FRACTION_OF_SCREEN  = 0.50f;
    private static final float MAX_HEIGHT_FRACTION_OF_SCREEN = 0.60f;
    // 9:16 — same aspect as the static reel-share bubble card.
    private static final float ASPECT_W = 9f;
    private static final float ASPECT_H = 16f;

    /**
     * Returns {widthPx, heightPx} for the peek player, sized relative to
     * the current screen rather than a fixed dp value (see doc above).
     */
    private static int[] computeCardSizePx(Context context) {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenWidthPx  = dm.widthPixels;
        int screenHeightPx = dm.heightPixels;

        int widthPx  = Math.round(screenWidthPx * WIDTH_FRACTION_OF_SCREEN);
        int heightPx = Math.round(widthPx * (ASPECT_H / ASPECT_W));

        int maxHeightPx = Math.round(screenHeightPx * MAX_HEIGHT_FRACTION_OF_SCREEN);
        if (heightPx > maxHeightPx) {
            heightPx = maxHeightPx;
            widthPx  = Math.round(heightPx * (ASPECT_W / ASPECT_H));
        }
        return new int[]{widthPx, heightPx};
    }

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
                int[] sizePx = computeCardSizePx(sourceView.getContext());
                int cardWidthPx   = sizePx[0];
                int videoHeightPx = sizePx[1];

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

    /**
     * BUG FIX: dismisses this activity's reel-peek popup (if any) and, most
     * importantly, releases its ExoPlayer — call from the hosting chat
     * screen's onPause()/onStop()/onDestroy().
     *
     * ROOT CAUSE of "player disappears but audio keeps playing": show()
     * above stashes the controller in the static CONTROLLERS map, but
     * nothing ever told that controller to tear down when the host screen
     * stopped being visible — unlike UserReelsActivity (the original owner
     * of this popup), which calls peekController.dismiss() from its own
     * onPause(). A PopupWindow is NOT auto-dismissed by the Android
     * lifecycle: turning the screen off, hitting Home, or navigating to
     * another chat/reel just hides the window's surface — the ExoPlayer
     * inside keeps decoding/playing audio in the background regardless,
     * and since the popup is still "showing" as far as the controller is
     * concerned, leaving the chat or opening a different reel never
     * replaced or stopped it either.
     *
     * Fix: chat screens now call this from onPause() (covers screen-off,
     * Home, app-switch, and navigating to another chat — onPause always
     * fires first in every one of those cases) so the controller's own
     * dismiss() runs and releases the player back to the pool immediately,
     * instead of leaving it playing invisibly until the process happens to
     * die.
     */
    public static void dismiss(@Nullable Activity activity) {
        if (activity == null) return;
        Object controller = CONTROLLERS.remove(activity);
        if (controller == null) return;
        try {
            Method dismissMethod = controller.getClass().getMethod("dismiss");
            dismissMethod.invoke(controller);
        } catch (Throwable ignored) {
            // Best-effort — an older/mismatched feature-reels build on the
            // classpath should never crash the chat screen's onPause().
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