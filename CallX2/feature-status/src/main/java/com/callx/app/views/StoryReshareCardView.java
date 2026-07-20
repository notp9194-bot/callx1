package com.callx.app.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.status.R;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Instagram-style "Reshare Card" sticker that appears both during story creation
 * (draggable) and during story viewing (static overlay).
 *
 * Shows: [thumbnail | @ownerName | content-type badge | "View Original →"]
 */
public class StoryReshareCardView extends FrameLayout {

    private ImageView      ivThumb;
    private CircleImageView ivOwnerAvatar;
    private TextView       tvOwnerName;
    private TextView       tvBadge;
    private TextView       tvViewOriginal;

    // Drag tracking
    private float touchDownRawX, touchDownRawY;
    private float startTransX, startTransY;
    private boolean draggable = false;

    // ── Constructors ──────────────────────────────────────────────────────

    public StoryReshareCardView(Context context) {
        super(context);
        init(context);
    }

    public StoryReshareCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StoryReshareCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        inflate(context, R.layout.view_story_reshare_card, this);
        ivThumb       = findViewById(R.id.iv_reshare_thumb);
        ivOwnerAvatar = findViewById(R.id.iv_reshare_owner_avatar);
        tvOwnerName   = findViewById(R.id.tv_reshare_owner_name);
        tvBadge       = findViewById(R.id.tv_reshare_badge);
        tvViewOriginal= findViewById(R.id.tv_view_original);

        // Elevation & shadow
        setElevation(8f);

        // Touch listener for drag
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!draggable) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchDownRawX = event.getRawX();
                        touchDownRawY = event.getRawY();
                        startTransX   = getTranslationX();
                        startTransY   = getTranslationY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchDownRawX;
                        float dy = event.getRawY() - touchDownRawY;
                        // Constrain to parent bounds
                        View parent = (View) getParent();
                        if (parent != null) {
                            float newX = startTransX + dx;
                            float newY = startTransY + dy;
                            float maxX = parent.getWidth()  - getWidth();
                            float maxY = parent.getHeight() - getHeight();
                            newX = Math.max(0, Math.min(newX, maxX));
                            newY = Math.max(0, Math.min(newY, maxY));
                            setTranslationX(newX);
                            setTranslationY(newY);
                        } else {
                            setTranslationX(startTransX + dx);
                            setTranslationY(startTransY + dy);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Consume if it was a drag (not a tap)
                        float totalMove = Math.abs(event.getRawX() - touchDownRawX)
                                        + Math.abs(event.getRawY() - touchDownRawY);
                        return totalMove > 10;
                }
                return false;
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Populate the card with reshare data.
     * @param thumbUrl     Thumbnail URL of the original content
     * @param ownerName    Display name of the original creator
     * @param ownerAvatar  Avatar URL of the original creator
     * @param contentType  "reel", "post", or "channel_post"
     */
    public void setReshareData(String thumbUrl, String ownerName,
                               String ownerAvatar, String contentType) {
        Context ctx = getContext();

        // Thumbnail
        if (ivThumb != null && thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(ctx)
                 .load(thumbUrl)
                 .apply(new RequestOptions().centerCrop().placeholder(R.color.black))
                 .into(ivThumb);
        }

        // Owner avatar
        if (ivOwnerAvatar != null && ownerAvatar != null && !ownerAvatar.isEmpty()) {
            Glide.with(ctx)
                 .load(ownerAvatar)
                 .apply(new RequestOptions().circleCrop().placeholder(R.drawable.ic_person))
                 .into(ivOwnerAvatar);
        }

        // Owner name
        if (tvOwnerName != null) {
            tvOwnerName.setText(ownerName != null && !ownerName.isEmpty()
                    ? "@" + ownerName : "@user");
        }

        // Badge
        if (tvBadge != null) {
            String badge = "post".equals(contentType) ? "Post"
                         : "channel_post".equals(contentType) ? "Channel"
                         : "Reel";
            tvBadge.setText(badge);
        }
    }

    /** Enable or disable drag behaviour. */
    public void setDraggable(boolean draggable) {
        this.draggable = draggable;
    }

    /** Set the "View Original →" click listener. */
    public void setOnViewOriginalClickListener(OnClickListener l) {
        if (tvViewOriginal != null) tvViewOriginal.setOnClickListener(l);
    }
}
