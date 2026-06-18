package com.callx.app.feed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.callx.app.reels.R;

/**
 * GamesCardFragment — Full-screen "Mini Games" card injected into the Reels
 * vertical feed (ReelsFragment / ReelsAdapter) once every 3 real reels,
 * Instagram/YouTube-Playables-shelf style.
 *
 * This fragment is intentionally self-contained:
 *  - feature-reels does NOT have a compile-time dependency on feature-games,
 *    so GameActivity (com.callx.app.game.GameActivity, in the feature-games
 *    module) is launched via an EXPLICIT Intent built from the app's
 *    applicationId + fully-qualified class name. Both modules are merged
 *    into the same APK/process by the `app` module, so this resolves fine
 *    at runtime with zero reflection needed.
 *  - It carries no ReelModel / Firebase data — ReelsAdapter decides purely
 *    by position whether to show this card or a real ReelPlayerFragment,
 *    so pagination, blocking, and playback-control logic in ReelsFragment
 *    are completely untouched.
 *
 * To add more games later, just add another card block to
 * fragment_reels_games_card.xml + wire its click listener here, matching
 * the GamesHubActivity.buildGameList() entries.
 */
public class GamesCardFragment extends Fragment {

    private static final String GAME_ACTIVITY_PACKAGE   = "com.callx.app";
    private static final String GAME_ACTIVITY_CLASS_NAME = "com.callx.app.game.GameActivity";

    // Keep these in sync with GamesHubActivity.buildGameList()
    private static final String URL_BUBBLE_POP  = "https://callx-server.onrender.com/bubble-pop-game.html";
    private static final String URL_CAR_RACING  = "https://callx-server.onrender.com/car.html";

    public static GamesCardFragment newInstance() {
        return new GamesCardFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reels_games_card, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View card1 = view.findViewById(R.id.card_game_1);
        View play1 = view.findViewById(R.id.btn_game1_play);
        View card2 = view.findViewById(R.id.card_game_2);
        View play2 = view.findViewById(R.id.btn_game2_play);

        View.OnClickListener launchBubblePop = v -> launchGame(URL_BUBBLE_POP, "Bubble Pop");
        View.OnClickListener launchCarRacing = v -> launchGame(URL_CAR_RACING, "Car Racing");

        if (card1 != null) card1.setOnClickListener(launchBubblePop);
        if (play1 != null) play1.setOnClickListener(launchBubblePop);
        if (card2 != null) card2.setOnClickListener(launchCarRacing);
        if (play2 != null) play2.setOnClickListener(launchCarRacing);
    }

    private void launchGame(String url, String title) {
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(GAME_ACTIVITY_PACKAGE, GAME_ACTIVITY_CLASS_NAME));
            i.putExtra("url", url);
            i.putExtra("title", title);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Game open nahi ho paya", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * GamesCardFragment shows no video/photo — explicitly a no-op so that if
     * any caller mistakenly treats it like a ReelPlayerFragment, nothing breaks.
     */
    public void setUserVisibleHint(boolean visible) {
        // no-op — present for API symmetry with ReelPlayerFragment; this
        // fragment has no playback state to pause/resume.
    }
}
