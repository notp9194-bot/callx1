package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.LinearGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.ViewParent;

import androidx.appcompat.widget.AppCompatImageButton;

import com.callx.app.chat.R;

/**
 * Header icon button whose glass plate (backdrop blur, tint, gloss, rim) is
 * painted by the parent {@link GlassHeaderLayout}. This view itself only
 * draws the icon, so the glass re-renders every frame the content behind it
 * changes, while the icon keeps its own cached display list.
 */
public class GlassImageButton extends AppCompatImageButton {

    private boolean hasTint;
    private int tint;
    private float cornerRadiusPx = -1f;
    private float insetPx;

    private GlassHeaderLayout host;

    private Shader gloss, rim;
    private float shaderW = -1f, shaderH = -1f;
    private boolean shaderDark;

    public GlassImageButton(Context context) {
        this(context, null);
    }

    public GlassImageButton(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.appcompat.R.attr.imageButtonStyle);
    }

    public GlassImageButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassImageButton);
            try {
                if (a.hasValue(R.styleable.GlassImageButton_glassTint)) {
                    hasTint = true;
                    tint = a.getColor(R.styleable.GlassImageButton_glassTint, 0);
                }
                cornerRadiusPx = a.getDimension(R.styleable.GlassImageButton_glassCornerRadius, -1f);
                insetPx = a.getDimension(R.styleable.GlassImageButton_glassInset, 0f);
            } finally {
                a.recycle();
            }
        }
    }

    float getGlassInsetPx() { return insetPx; }

    float resolveRadius(RectF r) {
        return cornerRadiusPx >= 0f ? cornerRadiusPx : Math.min(r.width(), r.height()) / 2f;
    }

    int resolveTint(boolean dark) {
        if (hasTint) return tint;
        return dark ? 0x26FFFFFF : 0x99FFFFFF;
    }

    Shader glossShader(float w, float h, boolean dark) {
        ensureShaders(w, h, dark);
        return gloss;
    }

    Shader rimShader(float w, float h, boolean dark) {
        ensureShaders(w, h, dark);
        return rim;
    }

    private void ensureShaders(float w, float h, boolean dark) {
        if (gloss != null && w == shaderW && h == shaderH && dark == shaderDark) return;
        shaderW = w; shaderH = h; shaderDark = dark;
        gloss = new LinearGradient(0, 0, 0, h,
                new int[]{ dark ? 0x40FFFFFF : 0x8CFFFFFF, 0x0CFFFFFF, 0x00FFFFFF, 0x14000000 },
                new float[]{ 0f, 0.42f, 0.6f, 1f }, Shader.TileMode.CLAMP);
        rim = new LinearGradient(0, 0, w, h,
                new int[]{ 0xF2FFFFFF, 0x30FFFFFF, 0x30FFFFFF, 0x8CFFFFFF },
                new float[]{ 0f, 0.45f, 0.55f, 1f }, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewParent p = getParent();
        while (p != null) {
            if (p instanceof GlassHeaderLayout) {
                host = (GlassHeaderLayout) p;
                host.registerGlass(this);
                break;
            }
            p = p.getParent();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (host != null) {
            host.unregisterGlass(this);
            host = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        setImageAlpha(isPressed() ? 190 : 255);
        if (host != null) host.invalidate();   // plate brightens while pressed
    }
}
